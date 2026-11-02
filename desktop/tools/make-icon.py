# 產生程式圖示：粉紅圓角方塊＋白色播放鍵（跟 Android 版一樣的配色）
# 用法：py tools/make-icon.py  → src/icon.png、src/icon.ico
import struct
import zlib
from pathlib import Path

ACCENT = (0xE0, 0x45, 0x7B)
SS = 4  # 超取樣倍數，邊緣才不會鋸齒


def inside_round_rect(x, y, size, r):
    cx = min(max(x, r), size - r)
    cy = min(max(y, r), size - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def inside_triangle(x, y, size):
    # 播放鍵：左邊直線、右邊尖角，稍微往右偏才會看起來置中
    ax, ay = size * 0.38, size * 0.28
    bx, by = size * 0.38, size * 0.72
    cx, cy = size * 0.74, size * 0.50

    def side(px, py, qx, qy, rx, ry):
        return (qx - px) * (ry - py) - (qy - py) * (rx - px)

    d1 = side(ax, ay, bx, by, x, y)
    d2 = side(bx, by, cx, cy, x, y)
    d3 = side(cx, cy, ax, ay, x, y)
    neg = d1 < 0 or d2 < 0 or d3 < 0
    pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)


def render(size):
    big = size * SS
    radius = big * 0.22
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            bg = fg = 0
            for sy in range(SS):
                for sx in range(SS):
                    px, py = x * SS + sx + 0.5, y * SS + sy + 0.5
                    if inside_round_rect(px, py, big, radius):
                        bg += 1
                        if inside_triangle(px, py, big):
                            fg += 1
            n = SS * SS
            a = bg / n
            w = fg / bg if bg else 0
            rgb = [round(c * (1 - w) + 255 * w) for c in ACCENT]
            row += bytes(rgb + [round(a * 255)])
        rows.append(bytes(row))
    return rows


def png(rows, size):
    raw = b''.join(b'\x00' + r for r in rows)

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


def ico(images):
    # ICO 檔可以直接放 PNG（Windows Vista 以後都支援）
    header = struct.pack('<HHH', 0, 1, len(images))
    offset = 6 + 16 * len(images)
    entries = b''
    blobs = b''
    for size, data in images:
        entries += struct.pack('<BBBBHHII', size % 256, size % 256, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
        blobs += data
    return header + entries + blobs


def main():
    out = Path(__file__).resolve().parent.parent / 'src'
    images = []
    for size in (256, 64, 48, 32, 16):
        data = png(render(size), size)
        images.append((size, data))
        if size == 256:
            (out / 'icon.png').write_bytes(data)
    (out / 'icon.ico').write_bytes(ico(images))
    print('ok', out)


if __name__ == '__main__':
    main()
