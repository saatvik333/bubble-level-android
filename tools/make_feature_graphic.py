"""Renders the 1024x500 Play Store feature graphic in the app's Aurum theme (see Palette.java)."""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "featureGraphic.png")
FONTS = os.path.join(ROOT, "assets", "fonts")

BG = (0x2B, 0x2A, 0x27)
FG = (0xE9, 0xE5, 0xD9)
DIM = (0x9C, 0x97, 0x8A)
LINE = (0x4A, 0x47, 0x41)
ACCENT = (0xBC, 0xAD, 0x7E)

W, H, SS = 1024, 500, 4


def main() -> None:
    im = Image.new("RGB", (W * SS, H * SS), BG)
    d = ImageDraw.Draw(im)

    def s(v):
        return round(v * SS)

    # Instrument: the surface-mode bullseye, bubble resting just off centre.
    cx, cy, r = 760, 250, 196
    hair = s(1.5)
    d.ellipse([s(cx - r), s(cy - r), s(cx + r), s(cy + r)], outline=LINE, width=hair)
    d.ellipse([s(cx - 88), s(cy - 88), s(cx + 88), s(cy + 88)], outline=LINE, width=hair)
    d.line([s(cx - r + 14), s(cy), s(cx - 52), s(cy)], fill=LINE, width=hair)
    d.line([s(cx + 52), s(cy), s(cx + r - 14), s(cy)], fill=LINE, width=hair)
    d.line([s(cx), s(cy - r + 14), s(cx), s(cy - 52)], fill=LINE, width=hair)
    d.line([s(cx), s(cy + 52), s(cx), s(cy + r - 14)], fill=LINE, width=hair)
    d.ellipse([s(cx - 46), s(cy - 46), s(cx + 46), s(cy + 46)], outline=DIM, width=s(2))
    bx, by, br = cx + 16, cy - 10, 40
    d.ellipse([s(bx - br), s(by - br), s(bx + br), s(by + br)], fill=ACCENT)
    d.line([s(bx - 12), s(by), s(bx + 12), s(by)], fill=BG, width=s(1.5))
    d.line([s(bx), s(by - 12), s(bx), s(by + 12)], fill=BG, width=s(1.5))

    light = ImageFont.truetype(os.path.join(FONTS, "rubik_300.ttf"), s(74))
    regular = ImageFont.truetype(os.path.join(FONTS, "rubik_400.ttf"), s(26))
    d.text((s(80), s(178)), "Bubble Level", font=light, fill=FG)
    d.text((s(84), s(286)), "Calibrates on any firm surface", font=regular, fill=DIM)

    im.resize((W, H), Image.LANCZOS).save(OUT, optimize=True)


if __name__ == "__main__":
    main()
