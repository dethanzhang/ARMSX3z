#!/usr/bin/env python3
"""make_icons.py —— 把一张方形图换成定制版的启动器图标（不改上游资源）。

原理：Android 资源合并时 **flavor 源集优先于 main**，所以把图标写进
`app/src/github/res/...` 就会覆盖 `app/src/main/res/...` 的同名资源，
完全不用碰上游文件（Sync fork 依然零冲突）。

用法：
    python3 tools/custom/make_icons.py ~/Downloads/icon.png
    python3 tools/custom/make_icons.py icon.jpg --flavor github --no-mark

产物（相对 android/armsx3-ui/）：
    app/src/github/res/mipmap-mdpi/ic_launcher.png        48
    app/src/github/res/mipmap-hdpi/ic_launcher.png        72
    app/src/github/res/mipmap-xhdpi/ic_launcher.png       96
    app/src/github/res/mipmap-xxhdpi/ic_launcher.png      144
    app/src/github/res/mipmap-xxxhdpi/ic_launcher.png     192
    .../ic_launcher_round.png                             同上尺寸（圆形遮罩）
    app/src/github/res/drawable/ic_armsx3_mark.png        192（应用内左上角的标记）

建议素材：≥512×512 的方形 PNG（带透明底更好）；非方形会居中裁成方形。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("需要 Pillow：用带 PIL 的解释器跑，例如 /Users/zdc/.pyenv/shims/python3")

REPO = Path(__file__).resolve().parents[2]          # ARMSX3z/
APP = REPO / "android" / "armsx3-ui" / "app"

# Android 密度 → 启动器图标像素（基准 48dp）
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def square(img: Image.Image) -> Image.Image:
    """居中裁成方形（保留最大面积）。"""
    w, h = img.size
    if w == h:
        return img
    side = min(w, h)
    return img.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side, (h - side) // 2 + side))


def rounded(img: Image.Image) -> Image.Image:
    """圆形遮罩（roundIcon 用）。4x 超采样再缩回来，边缘才不毛躁。"""
    side = img.size[0]
    big = img.resize((side * 4, side * 4), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (side * 4, side * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, side * 4 - 1, side * 4 - 1), fill=255)
    big.putalpha(mask)
    return big.resize((side, side), Image.LANCZOS)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("source", help="方形源图（PNG/JPG/WebP）")
    ap.add_argument("--flavor", default="github", choices=("github", "play"))
    ap.add_argument("--no-mark", action="store_true", help="同时覆盖应用内标记 ic_armsx3_mark")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    src = Path(a.source).expanduser()
    if not src.is_file():
        return f"找不到源图：{src}" and 1

    base = square(Image.open(src).convert("RGBA"))
    if min(base.size) < 192:
        print(f"警告：源图 {base.size} 偏小，建议 ≥512×512（放大会糊）", file=sys.stderr)

    res = APP / "src" / a.flavor / "res"
    written = []

    for density, px in DENSITIES.items():
        normal = base.resize((px, px), Image.LANCZOS)
        round_ = rounded(normal)

        for name, img in (("ic_launcher", normal), ("ic_launcher_round", round_)):
            out = res / f"mipmap-{density}" / f"{name}.png"
            written.append((out, px))
            if not a.dry_run:
                out.parent.mkdir(parents=True, exist_ok=True)
                img.save(out, optimize=True)

    if not a.no_mark:
        out = res / "drawable" / "ic_armsx3_mark.png"
        written.append((out, 192))
        if not a.dry_run:
            out.parent.mkdir(parents=True, exist_ok=True)
            base.resize((192, 192), Image.LANCZOS).save(out, optimize=True)

    for out, px in written:
        rel = out.relative_to(REPO)
        print(f"  {'(dry) ' if a.dry_run else ''}{rel}  {px}px")

    print(f"\n共 {len(written)} 个文件{'（未写盘）' if a.dry_run else ''}；"
          f"构建时 {a.flavor} flavor 会覆盖 main 里的同名资源。")
    print("提交后 CI 出来的 APK 就是新图标；原版 ARMSX3 的图标不受影响。")
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
