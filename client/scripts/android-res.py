"""生成安卓 APP 的图标和启动画面（需要 Python 3 和 Pillow）。

用 build/icon.png（应用图标）和 src/assets/art/sidebar-wheat.webp（线描麦穗）生成：
- 各分辨率的 ic_launcher.png、ic_launcher_round.png（老式图标）和 ic_launcher_foreground.png（自适应图标前景）
- 各分辨率、横竖屏的 splash.png（启动画面：深林绿底，中间是应用图标，底下淡淡的麦穗）

用法：在 client 目录下运行 python3 scripts/android-res.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "android" / "app" / "src" / "main" / "res"
ICON = Image.open(ROOT / "build" / "icon.png").convert("RGBA")
WHEAT = Image.open(ROOT / "src" / "assets" / "art" / "sidebar-wheat.webp").convert("RGBA")
FOREST_TOP = (36, 81, 61)
FOREST_BOTTOM = (22, 55, 40)
# 自适应图标的背景色：和图标边缘的深绿一致，系统切成圆形、方形时看不出接缝
ADAPTIVE_BG = "#123F2C"

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SPLASH = {"mdpi": (320, 480), "hdpi": (480, 800), "xhdpi": (720, 1280), "xxhdpi": (960, 1600), "xxxhdpi": (1280, 1920)}


def round_icon(size: int) -> Image.Image:
    im = ICON.resize((size * 4, size * 4), Image.LANCZOS)
    # 圆形图标：去掉圆角方块外沿，裁成圆
    inset = int(size * 4 * 0.06)
    im = im.crop((inset, inset, size * 4 - inset, size * 4 - inset)).resize((size * 4, size * 4), Image.LANCZOS)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, im.width - 1, im.height - 1), fill=255)
    im.putalpha(mask)
    return im.resize((size, size), Image.LANCZOS)


def foreground(size: int) -> Image.Image:
    """自适应图标前景：108dp 画布，图标缩到 80% 放在中间，系统只显示中间 72dp"""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.80)
    canvas.alpha_composite(ICON.resize((inner, inner), Image.LANCZOS), ((size - inner) // 2, (size - inner) // 2))
    return canvas


def splash(w: int, h: int) -> Image.Image:
    bg = Image.new("RGB", (w, h))
    draw = ImageDraw.Draw(bg)
    for y in range(h):
        t = y / max(h - 1, 1)
        draw.line([(0, y), (w, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(FOREST_TOP, FOREST_BOTTOM)))
    bg = bg.convert("RGBA")
    # 底部淡淡的麦穗
    wheat = WHEAT.copy()
    ww = int(min(w, h) * 0.75)
    wheat = wheat.resize((ww, int(wheat.height * ww / wheat.width)), Image.LANCZOS)
    alpha = wheat.getchannel("A").point(lambda a: int(a * 0.22))
    wheat.putalpha(alpha)
    bg.alpha_composite(wheat, ((w - ww) // 2, h - wheat.height + int(wheat.height * 0.12)))
    # 中间的应用图标
    s = int(min(w, h) * 0.30)
    bg.alpha_composite(ICON.resize((s, s), Image.LANCZOS), ((w - s) // 2, (h - s) // 2 - int(h * 0.04)))
    return bg.convert("RGB")


def main() -> None:
    for density, size in LEGACY.items():
        folder = RES / f"mipmap-{density}"
        ICON.resize((size, size), Image.LANCZOS).save(folder / "ic_launcher.png")
        round_icon(size).save(folder / "ic_launcher_round.png")
        foreground(int(size * 108 / 48)).save(folder / "ic_launcher_foreground.png")
    for density, (w, h) in SPLASH.items():
        splash(w, h).save(RES / f"drawable-port-{density}" / "splash.png", optimize=True)
        splash(h, w).save(RES / f"drawable-land-{density}" / "splash.png", optimize=True)
    splash(480, 320).save(RES / "drawable" / "splash.png", optimize=True)
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_launcher_background">{ADAPTIVE_BG}</color>\n</resources>\n', encoding="utf-8")
    print("安卓图标和启动画面已生成")


if __name__ == "__main__":
    main()
