"""
Generate a NovelForge.ico icon file using raw binary ICO format.
Creates a 32x32 pixel icon with an orange-red flame/fire theme.
No external dependencies required — uses only struct and zlib.
"""
import struct
import zlib

def create_32x32_icon_data():
    """Create 32x32 RGBA pixel data for NovelForge fire/forge icon."""
    w, h = 32, 32
    pixels = []  # list of (B,G,R,A) tuples

    # Background: dark charcoal
    bg = (30, 30, 40, 255)
    # Forge orange: bright orange for the flame/fire
    orange = (0, 140, 255, 255)       # BGR: (0, 140, 255) = RGB(255, 140, 0)
    # Deep red: for the forge body
    deep_red = (50, 30, 200, 255)     # BGR: (50, 30, 200) = RGB(200, 30, 50)
    # Bright yellow: flame tip
    yellow = (0, 220, 255, 255)       # BGR: (0, 220, 255) = RGB(255, 220, 0)
    # Anvil gray: metallic gray
    gray = (160, 160, 170, 255)       # BGR: (160, 160, 170) = RGB(170, 160, 160)
    # Light gray highlight
    light_gray = (190, 190, 200, 255)
    # Spark white
    spark = (240, 240, 255, 255)

    for y in range(h):
        row = []
        for x in range(w):
            # Center coordinates (origin at top-left)
            cx = x - 15.5  # center around 15.5
            cy = y - 15.5

            # === Outer circle border (dark ring) ===
            dist = (cx**2 + cy**2) ** 0.5
            if dist > 14.5:
                row.append((0, 0, 0, 0))  # transparent outside
                continue
            if dist > 13.5:
                row.append(deep_red)  # red border ring
                continue

            # === Flame shape (top area, y < center) ===
            # Main flame: triangular shape centered at top
            flame_cx = cx
            flame_cy = cy + 3  # shift flame upward relative to center
            flame_dist = (flame_cx**2 + flame_cy**2) ** 0.5

            # Flame body: cone shape narrowing toward top
            if cy < -2:  # upper area
                flame_width = max(2, 10 - abs(cy + 2) * 1.2)  # narrows as goes up
                if abs(cx) < flame_width:
                    # Inner flame gradient
                    inner_dist = abs(cx) / flame_width
                    if inner_dist < 0.3:
                        row.append(yellow)  # bright center
                    elif inner_dist < 0.6:
                        row.append(orange)  # orange middle
                    else:
                        # Outer flame edge
                        fade = int(255 * (1 - inner_dist))
                        row.append((0, max(0, 140 - int(80*inner_dist)), fade, 255))
                    continue

            # === Anvil shape (lower area) ===
            if cy >= -2 and cy < 6:
                # Anvil body: wide base
                anvil_top = -2
                anvil_bottom = 6
                if cy < 2:  # anvil top (narrower)
                    anvil_width = 8
                else:  # anvil base (wider)
                    anvil_width = 11
                if abs(cx) < anvil_width:
                    if cy < 0:
                        row.append(gray)  # top surface
                    elif cy < 2:
                        row.append(light_gray)  # middle
                    else:
                        row.append(gray)  # base
                    continue
                # Anvil edges
                if abs(cx) < anvil_width + 1 and cy >= 2:
                    row.append((80, 80, 90, 255))  # dark edge
                    continue

            # === Sparks around flame ===
            # Small bright dots scattered near flame
            spark_positions = [
                (5, -10), (-5, -9), (7, -6), (-7, -7),
                (3, -12), (-4, -11), (8, -4), (-8, -5),
                (10, -3), (-10, -2), (6, -13), (-6, -12),
            ]
            is_spark = False
            for sx, sy in spark_positions:
                if abs(x - (sx + 16)) <= 0 and abs(y - (sy + 16)) <= 0:
                    row.append(spark)
                    is_spark = True
                    break
            if is_spark:
                continue

            # === Background fill inside circle ===
            row.append(bg)

        pixels.append(row)

    return w, h, pixels


def create_16x16_icon_data():
    """Create 16x16 RGBA pixel data (scaled down version)."""
    w, h = 32, 32
    big_w, big_h, big_pixels = create_32x32_icon_data()
    # Scale down by averaging 2x2 blocks
    small_pixels = []
    for y in range(0, h, 2):
        row = []
        for x in range(0, w, 2):
            # Average 4 pixels
            p1 = big_pixels[y][x]
            p2 = big_pixels[y][x+1] if x+1 < w else p1
            p3 = big_pixels[y+1][x] if y+1 < h else p1
            p4 = big_pixels[y+1][x+1] if (y+1 < h and x+1 < w) else p1
            avg = tuple(int(sum(v)/4) for v in zip(p1, p2, p3, p4))
            row.append(avg)
        small_pixels.append(row)
    return 16, 16, small_pixels


def pixels_to_bmp_data(w, h, pixels):
    """Convert RGBA pixels to BMP format data (bottom-up, BGRA)."""
    # BMP is stored bottom-up
    raw = bytearray()
    for y in range(h - 1, -1, -1):  # bottom to top
        for x in range(w):
            b, g, r, a = pixels[y][x]
            raw.extend([b, g, r, a])
        # Pad row to 4-byte boundary
        row_bytes = w * 4
        pad = (4 - row_bytes % 4) % 4
        raw.extend(b'\x00' * pad)

    # BMP info header (BITMAPINFOHEADER)
    bpp = 32
    compression = 0  # BI_RGB
    image_size = len(raw)
    header = struct.pack('<IIIHHIIIIII',
        40,          # header size
        w,           # width
        h * 2,       # height (2x for AND mask inclusion)
        1,           # planes
        bpp,         # bits per pixel
        compression, # compression
        image_size,  # image size
        0,           # X pixels per meter
        0,           # Y pixels per meter
        0,           # colors used
        0            # important colors
    )

    return header + bytes(raw)


def create_ico_file(images):
    """
    Create ICO file from list of (width, height, pixels) tuples.
    Format: ICONDIR header + ICONDIRENTRY array + image data.
    """
    # Prepare BMP data for each image
    bmp_datas = []
    for w, h, pixels in images:
        bmp_datas.append(pixels_to_bmp_data(w, h, pixels))

    # Calculate offsets
    header_size = 6
    entry_size = 16
    data_offset = header_size + entry_size * len(images)

    offsets = []
    current_offset = data_offset
    for bmp_data in bmp_datas:
        offsets.append(current_offset)
        current_offset += len(bmp_data)

    # ICONDIR header
    ico = struct.pack('<HHH',
        0,           # reserved
        1,           # type: ICO
        len(images)  # count
    )

    # ICONDIRENTRY for each image
    for i, (w, h, pixels) in enumerate(images):
        entry = struct.pack('<BBBBHHII',
            w if w < 256 else 0,   # width (0 = 256)
            h if h < 256 else 0,   # height (0 = 256)
            0,                      # color palette count
            0,                      # reserved
            1,                      # color planes
            32,                     # bits per pixel
            len(bmp_datas[i]),      # data size
            offsets[i]              # data offset
        )
        ico += entry

    # Append all image data
    for bmp_data in bmp_datas:
        ico += bmp_data

    return ico


def main():
    # Create 32x32 and 16x16 icon images
    w1, h1, pixels1 = create_32x32_icon_data()
    w2, h2, pixels2 = create_16x16_icon_data()

    images = [(w1, h1, pixels1), (w2, h2, pixels2)]
    ico_data = create_ico_file(images)

    output_path = "novelforge.ico"
    with open(output_path, 'wb') as f:
        f.write(ico_data)

    print(f"Created {output_path} ({len(ico_data)} bytes)")
    print(f"  - 32x32 image: {w1}x{h1}")
    print(f"  - 16x16 image: {w2}x{h2}")


if __name__ == '__main__':
    main()
