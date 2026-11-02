"""Writes the JPEG XL files of JxlDecoderTest into ../assets/jxl.

Usage: python make_test_images.py <path to cjxl>

The pictures are drawn here and written as PNG (APNG for the animation, PGM for
the tall one) with the standard library only, then encoded with cjxl from the
libjxl 0.12.0 release (jxl-x64-windows-static.zip). cjxl is a tool of the build
machine and is not part of the repository.
"""

import pathlib
import struct
import subprocess
import sys
import tempfile
import zlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "assets" / "jxl"

RED = (220, 30, 40)
GREEN = (30, 200, 60)
BLUE = (40, 60, 210)
WHITE = (255, 255, 255)


def chunk(kind, data):
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))


def scanlines(rows):
    return zlib.compress(b"".join(b"\x00" + bytes(row) for row in rows), 9)


def png(width, height, color_type, rows, frames=None):
    """color_type 0 is gray, 2 is RGB, 6 is RGBA. frames, if given, lists (rows, delay ms) of an APNG."""
    out = [b"\x89PNG\r\n\x1a\n", chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0))]
    if frames is None:
        out.append(chunk(b"IDAT", scanlines(rows)))
    else:
        out.append(chunk(b"acTL", struct.pack(">II", len(frames), 0)))
        sequence = 0
        for index, (frame_rows, delay) in enumerate(frames):
            control = struct.pack(">IIIIIHHBB", sequence, width, height, 0, 0, delay, 1000, 0, 0)
            out.append(chunk(b"fcTL", control))
            sequence += 1
            data = scanlines(frame_rows)
            if index == 0:
                out.append(chunk(b"IDAT", data))
            else:
                out.append(chunk(b"fdAT", struct.pack(">I", sequence) + data))
                sequence += 1
    out.append(chunk(b"IEND", b""))
    return b"".join(out)


def quadrants(width, height):
    """Red, green on top and blue, white below."""
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            top, left = y < height // 2, x < width // 2
            row += RED if top and left else GREEN if top else BLUE if left else WHITE
        rows.append(row)
    return rows


def pgm(width, height, value):
    """libpng refuses more than a million rows, so the tall image goes to cjxl as a binary PGM."""
    return b"P5 %d %d 255\n" % (width, height) + bytes([value]) * (width * height)


def solid(width, height, pixel):
    return [list(pixel) * width for _ in range(height)]


def images():
    gray = [[(x * 8 + y) % 256 for x in range(32)] for y in range(16)]
    alpha = [[255, 0, 0, 128] * 16 for _ in range(8)]
    return {
        # Lossy, so XYB. A whole sample-size-2 decode averages each quadrant down.
        "lossy_quadrants.jxl": (png(256, 128, 2, quadrants(256, 128)), ["-d", "1"]),
        "lossless_gray.jxl": (png(32, 16, 0, gray), ["-d", "0"]),
        "lossless_alpha.jxl": (png(16, 8, 6, alpha), ["-d", "0"]),
        "lossless_container.jxl": (png(64, 32, 2, quadrants(64, 32)), ["-d", "0", "--container=1"]),
        "animation.jxl": (
            png(24, 24, 2, None, frames=[(solid(24, 24, RED), 100), (solid(24, 24, BLUE), 100)]),
            ["-d", "0"],
        ),
        # One pixel wide and 2^20 + 1 high: a side past the plausible limit of docs/spec.md section 3.7.
        "too_tall.jxl": (pgm(1, (1 << 20) + 1, 0), ["-d", "0"]),
    }


def main():
    cjxl = sys.argv[1]
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, (data, options) in images().items():
            source = pathlib.Path(tmp) / (name + (".pgm" if data.startswith(b"P5") else ".png"))
            source.write_bytes(data)
            subprocess.run([cjxl, str(source), str(OUT / name), "--quiet", *options], check=True)
            print(name, (OUT / name).stat().st_size)


if __name__ == "__main__":
    main()
