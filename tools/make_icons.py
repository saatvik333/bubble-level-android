"""Renders every raster of the icon from one definition (mirrors res/drawable/ic_fg.xml):
legacy (pre-API 26) launcher PNGs and the 512 px Play Store icon."""
import os
import sys
from PIL import Image, ImageDraw

BG = (0x2B, 0x2A, 0x27, 255)
DISC = (0xBC, 0xAD, 0x7E, 255)
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
STORE_ICON = os.path.join(os.path.dirname(__file__), "..", "fastlane", "metadata", "android", "en-US",
                          "images", "icon.png")


def render(size: int, store: bool = False) -> Image.Image:
    """Launcher: the 72-unit visible circle of the 108-unit adaptive canvas fills 92% of the icon.
    Store: a full-bleed square (Play applies its own corner mask) showing the central 80 units."""
    ss = 8
    S = size * ss
    im = Image.new("RGBA", (S, S), BG if store else (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    k = S / 80.0 if store else 0.92 * S / 72.0
    ox = S / 2 - 54 * k

    def P(x, y):
        return (ox + x * k, ox + y * k)

    if not store:
        r = 0.92 * S / 2
        d.ellipse([S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r], fill=BG)
    d.ellipse([*P(30, 30), *P(78, 78)], fill=DISC)
    # vial outline, bubble, level marks
    w = max(1, round(1.8 * k))
    d.rounded_rectangle([*P(35, 48), *P(73, 60)], radius=6 * k, outline=BG, width=w)
    d.rounded_rectangle([*P(48, 50.5), *P(60, 57.5)], radius=3.5 * k, fill=BG)
    m = max(1, round(1.4 * k))
    d.line([P(45.5, 50.5), P(45.5, 57.5)], fill=BG, width=m)
    d.line([P(62.5, 50.5), P(62.5, 57.5)], fill=BG, width=m)
    return im.resize((size, size), Image.LANCZOS)


def main(res_dir: str) -> None:
    for name, px in SIZES.items():
        out = os.path.join(res_dir, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        render(px).save(os.path.join(out, "ic_launcher.png"), optimize=True)
    render(512, store=True).save(STORE_ICON, optimize=True)


if __name__ == "__main__":
    main(sys.argv[1])
