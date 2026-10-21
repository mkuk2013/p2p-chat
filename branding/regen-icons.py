#!/usr/bin/env python3
"""Regenerate all P2P Chat launcher icons + splash logo from the stored logo.

The master logo PNG travels inside this repo as base64 text
(branding/logo-512.b64.txt) so the repository stays complete even though
binary files were not part of the original upload route. Run:

    python3 branding/regen-icons.py

Requires Pillow (pip install pillow). Outputs are written into
branding/icons/ (mipmaps + adaptive foreground), branding/p2pchat-logo-512.png
and copied into app/src/main/res/ when that folder exists.
"""
import base64, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRANDING = os.path.join(ROOT, "branding")

def main():
    try:
        from PIL import Image
    except ImportError:
        sys.exit("Pillow required: pip install pillow")
    b64 = "".join(open(os.path.join(BRANDING, "logo-512.b64.txt")).read().split())
    png_path = os.path.join(BRANDING, "p2pchat-logo-512.png")
    open(png_path, "wb").write(base64.b64decode(b64))
    logo = Image.open(png_path).convert("RGBA")
    sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
             "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
    res = os.path.join(ROOT, "app", "src", "main", "res")
    for folder, s in sizes.items():
        out_dir = os.path.join(BRANDING, "icons", folder)
        os.makedirs(out_dir, exist_ok=True)
        icon = logo.resize((s, s), Image.LANCZOS)
        icon.save(os.path.join(out_dir, "ic_launcher.png"))
        icon.save(os.path.join(out_dir, "ic_launcher_round.png"))
        if os.path.isdir(res):
            rd = os.path.join(res, folder)
            os.makedirs(rd, exist_ok=True)
            icon.save(os.path.join(rd, "ic_launcher.png"))
            icon.save(os.path.join(rd, "ic_launcher_round.png"))
    fg = Image.new("RGBA", (432, 432), (4, 16, 32, 255))
    fg.alpha_composite(logo.resize((330, 330), Image.LANCZOS), (51, 51))
    fg.save(os.path.join(BRANDING, "icons", "ic_launcher_foreground_432.png"))
    if os.path.isdir(res):
        nd = os.path.join(res, "drawable-nodpi")
        os.makedirs(nd, exist_ok=True)
        fg.save(os.path.join(nd, "ic_launcher_foreground.png"))
        logo.save(os.path.join(nd, "logo_splash.png"))
    print("Icons regenerated from branding/logo-512.b64.txt")

if __name__ == "__main__":
    main()
