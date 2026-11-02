// libFuzzer target for the JPEG XL decoder. It calls libjxl as jxl_jni.cc does: the decoder gets a memory manager that
// refuses allocations past a limit, a parallel runner that runs the work one task after another on the calling thread,
// and an output callback that receives RGBA8 scanlines for the first frame. The first input byte picks the limit, so
// that the refusal paths are reached. The JNI file itself includes the Android bitmap API and is not part of this.

#include <jxl/decode.h>

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

namespace {

struct Memory {
    size_t limit;
    size_t used = 0;
};

constexpr size_t kSizeField = 16;

void* Allocate(void* opaque, size_t size) {
    auto* memory = static_cast<Memory*>(opaque);
    if (size > memory->limit - memory->used) return nullptr;
    auto* block = static_cast<unsigned char*>(malloc(size + kSizeField));
    if (block == nullptr) return nullptr;
    memcpy(block, &size, sizeof size);
    memory->used += size;
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

JxlParallelRetCode RunSequentially(void*, void* jpegxl_opaque, JxlParallelRunInit init, JxlParallelRunFunction func,
                                   uint32_t start_range, uint32_t end_range) {
    JxlParallelRetCode init_result = init(jpegxl_opaque, 1);
    if (init_result != 0) return init_result;
    for (uint32_t i = start_range; i < end_range; i++) func(jpegxl_opaque, i, 0);
    return 0;
}

struct Sink {
    uint64_t pixels = 0;
};

void OnPixels(void* opaque, size_t, size_t, size_t count, const void* pixels) {
    auto* sink = static_cast<Sink*>(opaque);
    sink->pixels += count;
    // Read the scanline, so that the sanitizer sees an uninitialised or out-of-bounds row.
    volatile uint8_t last = static_cast<const uint8_t*>(pixels)[count * 4 - 1];
    (void)last;
}

// The app refuses an image over 2^28 pixels before it decodes; the fuzzer keeps to a size it can decode quickly.
constexpr uint64_t kMaxPixels = 1u << 22;

void Decode(const uint8_t* data, size_t size, size_t limit) {
    Memory memory = {limit};
    JxlMemoryManager manager = {&memory, Allocate, Free};
    JxlDecoder* decoder = JxlDecoderCreate(&manager);
    if (decoder == nullptr) return;
    if (JxlDecoderSubscribeEvents(decoder, JXL_DEC_BASIC_INFO | JXL_DEC_COLOR_ENCODING | JXL_DEC_FULL_IMAGE) ==
            JXL_DEC_SUCCESS &&
        JxlDecoderSetParallelRunner(decoder, RunSequentially, nullptr) == JXL_DEC_SUCCESS &&
        JxlDecoderSetInput(decoder, data, size) == JXL_DEC_SUCCESS) {
        JxlBasicInfo basic = {};
        Sink sink;
        for (bool running = true; running;) {
            switch (JxlDecoderProcessInput(decoder)) {
                case JXL_DEC_BASIC_INFO:
                    if (JxlDecoderGetBasicInfo(decoder, &basic) != JXL_DEC_SUCCESS ||
                        static_cast<uint64_t>(basic.xsize) * basic.ysize > kMaxPixels) {
                        running = false;
                    }
                    break;
                case JXL_DEC_COLOR_ENCODING: {
                    JxlColorEncoding srgb;
                    memset(&srgb, 0, sizeof srgb);
                    srgb.color_space = basic.num_color_channels == 1 ? JXL_COLOR_SPACE_GRAY : JXL_COLOR_SPACE_RGB;
                    srgb.white_point = JXL_WHITE_POINT_D65;
                    srgb.primaries = JXL_PRIMARIES_SRGB;
                    srgb.transfer_function = JXL_TRANSFER_FUNCTION_SRGB;
                    srgb.rendering_intent = JXL_RENDERING_INTENT_PERCEPTUAL;
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
                default:
                    running = false;
                    break;
            }
        }
    }
    JxlDecoderDestroy(decoder);
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size < 2) return 0;
    Decode(data + 1, size - 1, size_t{1} << (20 + data[0] % 8));
    return 0;
}
