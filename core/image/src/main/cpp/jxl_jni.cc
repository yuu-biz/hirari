// The JNI half of JxlDecoder.kt. Each call decodes on the calling thread with
// a sequential parallel runner, so libjxl calls back into this file only from
// inside the call, on that thread, with the JNIEnv of the call.
//
// No C++ exception crosses this file: it and libjxl build without exceptions.
// A Java exception thrown by the cancellation check is held while the decoder
// and the bitmap are released, and rethrown before returning.

#include <android/bitmap.h>
#include <jni.h>
#include <jxl/decode.h>

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace {

// Status codes shared with JxlDecoder.kt.
enum Status : jint {
    kOk = 0,
    kCorrupt = 1,
    kTruncated = 2,
    kOverLimit = 3,
    kCancelled = 4,
};

constexpr size_t kHeaderMemoryLimit = 16u << 20;

// Every allocation that libjxl makes through its JxlMemoryManager, and every
// one of this file, goes through here, and the one that would pass the limit is
// refused. libjxl turns the refusal into an error. The standard containers and
// `new` inside libjxl do not use the manager, so the limit does not count them;
// the caller leaves room for them in the budget (docs/spec.md section 3.6).
struct Memory {
    size_t limit;
    size_t used = 0;
    size_t peak = 0;
    bool refused = false;
};

// Keeps the 16-byte alignment of malloc for the block after the size.
constexpr size_t kSizeField = 16;

void* Allocate(void* opaque, size_t size) {
    auto* memory = static_cast<Memory*>(opaque);
    if (size > memory->limit - memory->used) {
        memory->refused = true;
        return nullptr;
    }
    auto* block = static_cast<unsigned char*>(malloc(size + kSizeField));
    if (block == nullptr) {
        memory->refused = true;
        return nullptr;
    }
    memcpy(block, &size, sizeof size);
    memory->used += size;
    if (memory->used > memory->peak) memory->peak = memory->used;
    return block + kSizeField;
}

void Free(void* opaque, void* address) {
    if (address == nullptr) return;
    auto* memory = static_cast<Memory*>(opaque);
    auto* block = static_cast<unsigned char*>(address) - kSizeField;
    size_t size;
    memcpy(&size, block, sizeof size);
    memory->used -= size;
    free(block);
}

// Asks the Kotlin side whether to stop. Once it says so, or throws, every
// later check stops without calling into Java again.
struct Cancellation {
    JNIEnv* env;
    jobject stop;
    jmethodID get_as_boolean;
    bool stopped = false;
    jthrowable thrown = nullptr;

    bool Check() {
        if (stopped) return true;
        jboolean answer = env->CallBooleanMethod(stop, get_as_boolean);
        if (env->ExceptionCheck()) {
            thrown = env->ExceptionOccurred();
            env->ExceptionClear();
            stopped = true;
        } else if (answer) {
            stopped = true;
        }
        return stopped;
    }
};

// Runs libjxl's tasks one by one on the calling thread and checks the
// cancellation before each, which makes the check per group of pixels.
JxlParallelRetCode RunSequentially(void* runner_opaque, void* jpegxl_opaque, JxlParallelRunInit init,
                                   JxlParallelRunFunction func, uint32_t start_range, uint32_t end_range) {
    auto* cancellation = static_cast<Cancellation*>(runner_opaque);
    JxlParallelRetCode init_result = init(jpegxl_opaque, 1);
    if (init_result != 0) return init_result;
    for (uint32_t i = start_range; i < end_range; i++) {
        if (cancellation->Check()) return JXL_PARALLEL_RET_RUNNER_ERROR;
        func(jpegxl_opaque, i, 0);
    }
    return 0;
}

struct DecoderDeleter {
    JxlDecoder* decoder;
    ~DecoderDeleter() {
        if (decoder != nullptr) JxlDecoderDestroy(decoder);
    }
};

struct Bytes {
    JNIEnv* env;
    jbyteArray array;
    jbyte* data;
    jsize size;

    // When the elements cannot be pinned or copied, data is null and an
    // OutOfMemoryError is pending, so no other JNI call may follow until the
    // caller returns.
    Bytes(JNIEnv* e, jbyteArray a)
        : env(e), array(a), data(e->GetByteArrayElements(a, nullptr)), size(data == nullptr ? 0 : e->GetArrayLength(a)) {}
    ~Bytes() {
        if (data != nullptr) env->ReleaseByteArrayElements(array, data, JNI_ABORT);
    }
    const uint8_t* bytes() const { return reinterpret_cast<const uint8_t*>(data); }
};

bool Transposed(JxlOrientation orientation) { return orientation >= JXL_ORIENT_TRANSPOSE; }

uint32_t Sampled(uint32_t side, uint32_t shift) {
    uint32_t sampled = (side + (1u << shift) - 1) >> shift;
    return sampled == 0 ? 1 : sampled;
}

jint FailureOf(const Memory& memory) { return memory.refused ? kOverLimit : kCorrupt; }

// Receives the decoded scanlines, already turned to the image's orientation,
// and writes them into the bitmap, averaging each 2^shift square of pixels into
// one. Android's RGBA_8888 holds premultiplied alpha.
struct Sink {
    uint8_t* pixels;
    uint32_t stride;
    uint32_t width;
    uint32_t height;
    uint32_t out_width;
    uint32_t out_height;
    uint32_t shift;
    bool premultiply;
    // Per output pixel and channel, the sum of its source pixels. Null when shift is 0.
    void* sums;
    bool out_of_range = false;

    // A sum holds at most 4^shift values of up to 255. 16 bits hold a 16 x 16 square and 32 bits a
    // 4096 x 4096 square; only larger squares need 64 bits, which any image of at most 2^28 pixels fits.
    static constexpr uint32_t kLargestShift16 = 4;
    static constexpr uint32_t kLargestShift32 = 12;
    static_assert((uint64_t{255} << (2 * kLargestShift16)) <= UINT16_MAX);
    static_assert((uint64_t{255} << (2 * (kLargestShift16 + 1))) > UINT16_MAX);
    static_assert((uint64_t{255} << (2 * kLargestShift32)) <= UINT32_MAX);
    static_assert((uint64_t{255} << (2 * (kLargestShift32 + 1))) > UINT32_MAX);
    static size_t SumSize(uint32_t shift) {
        if (shift <= kLargestShift16) return sizeof(uint16_t);
        return shift <= kLargestShift32 ? sizeof(uint32_t) : sizeof(uint64_t);
    }

    static uint32_t Premultiplied(uint32_t channel, uint32_t alpha) { return (channel * alpha + 127) / 255; }

    void Write(size_t x, size_t y, size_t count, const uint8_t* in) {
        if (y >= height || x > width || count > width - x) {
            out_of_range = true;
            return;
        }
        if (shift == 0) {
            uint8_t* out = pixels + y * stride + x * 4;
            if (!premultiply) {
                memcpy(out, in, count * 4);
                return;
            }
            for (size_t i = 0; i < count; i++, in += 4, out += 4) {
                uint32_t alpha = in[3];
                out[0] = static_cast<uint8_t>(Premultiplied(in[0], alpha));
                out[1] = static_cast<uint8_t>(Premultiplied(in[1], alpha));
                out[2] = static_cast<uint8_t>(Premultiplied(in[2], alpha));
                out[3] = static_cast<uint8_t>(alpha);
            }
            return;
        }
        switch (SumSize(shift)) {
            case sizeof(uint16_t):
                Accumulate<uint16_t>(x, y, count, in);
                break;
            case sizeof(uint32_t):
                Accumulate<uint32_t>(x, y, count, in);
                break;
            default:
                Accumulate<uint64_t>(x, y, count, in);
                break;
        }
    }

    template <typename Sum>
    void Accumulate(size_t x, size_t y, size_t count, const uint8_t* in) {
        Sum* row = static_cast<Sum*>(sums) + static_cast<size_t>(y >> shift) * out_width * 4;
        for (size_t i = 0; i < count; i++, in += 4) {
            Sum* sum = row + ((x + i) >> shift) * 4;
            uint32_t alpha = in[3];
            sum[0] = static_cast<Sum>(sum[0] + (premultiply ? Premultiplied(in[0], alpha) : in[0]));
            sum[1] = static_cast<Sum>(sum[1] + (premultiply ? Premultiplied(in[1], alpha) : in[1]));
            sum[2] = static_cast<Sum>(sum[2] + (premultiply ? Premultiplied(in[2], alpha) : in[2]));
            sum[3] = static_cast<Sum>(sum[3] + alpha);
        }
    }

    // Divides each sum by the number of source pixels its square holds, which
    // is less than 4^shift at the right and bottom edges.
    void Finish() {
        if (shift == 0) return;
        switch (SumSize(shift)) {
            case sizeof(uint16_t):
                Divide<uint16_t>();
                break;
            case sizeof(uint32_t):
                Divide<uint32_t>();
                break;
            default:
                Divide<uint64_t>();
                break;
        }
    }

    template <typename Sum>
    void Divide() {
        for (uint32_t oy = 0; oy < out_height; oy++) {
            uint32_t rows = ((oy + 1) << shift > height ? height : (oy + 1) << shift) - (oy << shift);
            const Sum* sum = static_cast<const Sum*>(sums) + static_cast<size_t>(oy) * out_width * 4;
            uint8_t* out = pixels + static_cast<size_t>(oy) * stride;
            for (uint32_t ox = 0; ox < out_width; ox++) {
                uint32_t columns = ((ox + 1) << shift > width ? width : (ox + 1) << shift) - (ox << shift);
                uint32_t count = rows * columns;
                for (int c = 0; c < 4; c++) *out++ = static_cast<uint8_t>((uint64_t{*sum++} + count / 2) / count);
            }
        }
    }
};

void OnPixels(void* opaque, size_t x, size_t y, size_t count, const void* pixels) {
    static_cast<Sink*>(opaque)->Write(x, y, count, static_cast<const uint8_t*>(pixels));
}

JxlColorEncoding Srgb(bool gray) {
    JxlColorEncoding encoding;
    memset(&encoding, 0, sizeof encoding);
    encoding.color_space = gray ? JXL_COLOR_SPACE_GRAY : JXL_COLOR_SPACE_RGB;
    encoding.white_point = JXL_WHITE_POINT_D65;
    encoding.primaries = JXL_PRIMARIES_SRGB;
    encoding.transfer_function = JXL_TRANSFER_FUNCTION_SRGB;
    encoding.rendering_intent = JXL_RENDERING_INTENT_PERCEPTUAL;
    return encoding;
}

jint Decode(JNIEnv* env, const Bytes& bytes, jobject bitmap, uint32_t shift, Memory& memory,
            Cancellation& cancellation, bool& has_alpha) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return kCorrupt;
    }
    JxlMemoryManager manager = {&memory, Allocate, Free};
    DecoderDeleter owner = {JxlDecoderCreate(&manager)};
    JxlDecoder* decoder = owner.decoder;
    if (decoder == nullptr) return kOverLimit;
    if (JxlDecoderSubscribeEvents(decoder, JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE) !=
            JXL_DEC_SUCCESS ||
        JxlDecoderSetParallelRunner(decoder, RunSequentially, &cancellation) != JXL_DEC_SUCCESS ||
        JxlDecoderSetInput(decoder, bytes.bytes(), static_cast<size_t>(bytes.size)) != JXL_DEC_SUCCESS) {
        return FailureOf(memory);
    }

    void* locked = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &locked) != ANDROID_BITMAP_RESULT_SUCCESS) return kCorrupt;
    Sink sink = {static_cast<uint8_t*>(locked), info.stride, 0, 0, info.width, info.height, shift, false, nullptr};
    JxlBasicInfo basic = {};
    jint result = kCorrupt;
    for (bool running = true; running;) {
        JxlDecoderStatus status = JxlDecoderProcessInput(decoder);
        if (cancellation.Check()) {
            result = kCancelled;
            break;
        }
        switch (status) {
            case JXL_DEC_BASIC_INFO: {
                if (JxlDecoderGetBasicInfo(decoder, &basic) != JXL_DEC_SUCCESS) {
                    running = false;
                    break;
                }
                bool transposed = Transposed(basic.orientation);
                sink.width = transposed ? basic.ysize : basic.xsize;
                sink.height = transposed ? basic.xsize : basic.ysize;
                // The bitmap was sized from an earlier header read of the same bytes.
                if (Sampled(sink.width, shift) != info.width || Sampled(sink.height, shift) != info.height) {
                    running = false;
                    break;
                }
                sink.premultiply = basic.alpha_bits != 0 && !basic.alpha_premultiplied;
                has_alpha = basic.alpha_bits != 0;
                if (shift > 0) {
                    size_t size = static_cast<size_t>(info.width) * info.height * 4 * Sink::SumSize(shift);
                    sink.sums = Allocate(&memory, size);
                    if (sink.sums == nullptr) {
                        result = kOverLimit;
                        running = false;
                        break;
                    }
                    memset(sink.sums, 0, size);
                }
                break;
            }
            case JXL_DEC_COLOR_ENCODING: {
                // Without a colour management system libjxl converts only from
                // XYB, which lossy images use. Other images keep their own
                // space, and the call fails for those that are not sRGB.
                JxlColorEncoding srgb = Srgb(basic.num_color_channels == 1);
                JxlDecoderSetPreferredColorProfile(decoder, &srgb);
                break;
            }
            case JXL_DEC_NEED_IMAGE_OUT_BUFFER: {
                JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
                if (JxlDecoderSetImageOutCallback(decoder, &format, OnPixels, &sink) != JXL_DEC_SUCCESS) {
                    running = false;
                }
                break;
            }
            case JXL_DEC_FULL_IMAGE:
                // The first frame is the whole result, also for an animation.
                if (!sink.out_of_range) {
                    sink.Finish();
                    result = kOk;
                }
                running = false;
                break;
            case JXL_DEC_NEED_MORE_INPUT:
                result = kTruncated;
                running = false;
                break;
            case JXL_DEC_ERROR:
                result = FailureOf(memory);
                running = false;
                break;
            default:
                running = false;
                break;
        }
    }
    if (sink.sums != nullptr) Free(&memory, sink.sums);
    AndroidBitmap_unlockPixels(env, bitmap);
    return result;
}

jint ReadHeader(const Bytes& bytes, jint* out) {
    Memory memory = {kHeaderMemoryLimit};
    JxlMemoryManager manager = {&memory, Allocate, Free};
    DecoderDeleter owner = {JxlDecoderCreate(&manager)};
    JxlDecoder* decoder = owner.decoder;
    if (decoder == nullptr) return kOverLimit;
    if (JxlDecoderSubscribeEvents(decoder, JXL_DEC_BASIC_INFO) != JXL_DEC_SUCCESS ||
        JxlDecoderSetInput(decoder, bytes.bytes(), static_cast<size_t>(bytes.size)) != JXL_DEC_SUCCESS) {
        return FailureOf(memory);
    }
    switch (JxlDecoderProcessInput(decoder)) {
        case JXL_DEC_BASIC_INFO: {
            JxlBasicInfo basic;
            if (JxlDecoderGetBasicInfo(decoder, &basic) != JXL_DEC_SUCCESS) return kCorrupt;
            bool transposed = Transposed(basic.orientation);
            uint32_t width = transposed ? basic.ysize : basic.xsize;
            uint32_t height = transposed ? basic.xsize : basic.ysize;
            // Sides past Int.MAX_VALUE are reported as 0, which Kotlin refuses as implausible.
            out[0] = width > INT32_MAX ? 0 : static_cast<jint>(width);
            out[1] = height > INT32_MAX ? 0 : static_cast<jint>(height);
            out[2] = basic.have_animation ? 1 : 0;
            return kOk;
        }
        case JXL_DEC_NEED_MORE_INPUT:
            return kTruncated;
        default:
            return FailureOf(memory);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL Java_io_github_yuubiz_hirari_core_image_JxlDecoder_nativeHeader(
    JNIEnv* env, jobject, jbyteArray array, jintArray header) {
    Bytes bytes(env, array);
    if (bytes.data == nullptr) return kOverLimit;
    jint out[3] = {0, 0, 0};
    jint status = ReadHeader(bytes, out);
    if (status == kOk) env->SetIntArrayRegion(header, 0, 3, out);
    return status;
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_yuubiz_hirari_core_image_JxlDecoder_nativeDecode(
    JNIEnv* env, jobject, jbyteArray array, jobject bitmap, jint shift, jlong memory_limit, jobject stop,
    jlongArray report) {
    if (shift < 0 || shift > 30 || memory_limit < 0) return kCorrupt;
    jclass supplier = env->GetObjectClass(stop);
    jmethodID get_as_boolean = env->GetMethodID(supplier, "getAsBoolean", "()Z");
    env->DeleteLocalRef(supplier);
    if (get_as_boolean == nullptr) return kCorrupt;
    Bytes bytes(env, array);
    if (bytes.data == nullptr) return kOverLimit;

    Memory memory = {static_cast<size_t>(memory_limit)};
    Cancellation cancellation = {env, stop, get_as_boolean};
    bool has_alpha = false;
    jint status = Decode(env, bytes, bitmap, static_cast<uint32_t>(shift), memory, cancellation, has_alpha);
    if (cancellation.thrown != nullptr) {
        env->Throw(cancellation.thrown);
        return kCancelled;
    }
    jlong out[2] = {static_cast<jlong>(memory.peak), has_alpha ? 1 : 0};
    env->SetLongArrayRegion(report, 0, 2, out);
    return status;
}
