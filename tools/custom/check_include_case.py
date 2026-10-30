#!/usr/bin/env python3
"""check_include_case.py —— 抓「只差大小写」的 #include（macOS 能编、Linux 编不过）。

为什么需要：ARMSX3 的作者在 macOS 上开发，APFS 大小写不敏感，所以
    #include "Emu/system.h"        ← 上游 sync.cpp 里就是这么写的
即使真实文件叫 `Emu/System.h` 也能编过；Linux 的 CI 上直接 fatal error。
2026-10-29 的第三次构建就死在这（已经用一个 shim 兜住）。

这个检查遍历仓库里所有 `#include "..."`，用**精确大小写**判断文件是否存在；
只有当「精确不存在、但忽略大小写存在」时才报——那正是这个 bug 的特征。

用法：
    python3 tools/custom/check_include_case.py            # 只报错
    python3 tools/custom/check_include_case.py --fix-hints # 额外打印建议
退出码：0 = 干净；1 = 有问题。
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

SKIP_PARTS = ("/.git/", "/3rdparty/", "/build-android/", "/.github/", "/node_modules/",
              # CI 才 clone 进来的第三方（各自的 include 根我们不管；见 README）
              "/libadrenotools/", "/librashader/")
INC_RE = re.compile(r'^\s*#\s*include\s+"([^"]+)"')
EXTS = (".cpp", ".h", ".hpp", ".c", ".cc", ".inl")

# 由 NDK/bionic 或工具链提供的头：即使仓库里有同名不同大小写的文件，也以系统头为准
NDK_HEADERS = {
    "elf.h", "jni.h", "link.h", "dlfcn.h", "unistd.h", "pthread.h",
    "android/log.h", "android/native_window.h", "GLES2/gl2.h", "GLES3/gl3.h", "EGL/egl.h",
}

# 我们自己的构建时 include 目录（apply_custom_hooks.py 会把它加进 rpcs3_emu）
EXTRA_INCLUDE_DIRS = ("tools/custom/shim", "rpcs3")


def norm(p: str) -> str:
    p = os.path.normpath(p).replace(os.sep, "/")
    return p[2:] if p.startswith("./") else p


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fix-hints", action="store_true", help="额外打印建议改法")
    a = ap.parse_args()

    files: list[str] = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        d = dirpath.replace(os.sep, "/")
        if any(x in d + "/" for x in SKIP_PARTS):
            dirnames[:] = []
            continue
        for fn in filenames:
            files.append(norm(os.path.relpath(os.path.join(dirpath, fn), ROOT)))

    exact = set(files)
    lower: dict[str, list[str]] = {}
    for p in files:
        lower.setdefault(p.lower(), []).append(p)

    problems = 0
    seen: set[tuple[str, str]] = set()

    for f in files:
        if not f.endswith(EXTS):
            continue
        try:
            text = (ROOT / f).read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue

        for line in text.splitlines():
            m = INC_RE.match(line)
            if not m:
                continue
            inc = m.group(1)

            cands = [norm(os.path.dirname(f) + "/" + inc)]
            cands += [norm(d + "/" + inc) for d in EXTRA_INCLUDE_DIRS]
            cands.append(norm(inc))
            if any(c in exact for c in cands):
                continue

            hits = lower.get(("rpcs3/" + inc).lower()) or lower.get(inc.lower())
            if not hits:
                continue        # 真的不存在（系统头 / 3rdparty / 生成文件），不归这里管

            key = (inc, hits[0])
            problems += 1
            if key in seen:
                continue
            seen.add(key)
            print(f'大小写不匹配："{inc}"  ←  {f}')
            print(f"    实际文件: {', '.join(hits)}")
            if a.fix_hints:
                print(f"    建议: 在 tools/custom/shim/{os.path.dirname(inc) or '.'}/ 放一个转发头，"
                      f"内容 #include <{hits[0].removeprefix('rpcs3/')}>")

    # ---- 尖括号形式：胶水层（app/src/main/cpp）的 savers 有自己的一套 include 根 ----
    glue = "android/armsx3-ui/app/src/main/cpp"
    glue_roots = [glue, glue + "/savers", glue + "/savers/compat", glue + "/savers/rslibs"]
    glue_roots += [glue + "/savers/" + d for d in
                   ("flux", "plasma", "solarwinds", "hyperspace", "lattice", "skyrocket")]
    angle = re.compile(r'^\s*#\s*include\s+<([^>]+)>')

    for f in files:
        if not f.startswith(glue) or not f.endswith(EXTS):
            continue
        try:
            text = (ROOT / f).read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for line in text.splitlines():
            m = angle.match(line)
            if not m:
                continue
            inc = m.group(1)
            if inc in NDK_HEADERS:
                continue
            if any(norm(r + "/" + inc) in exact for r in glue_roots) or norm(inc) in exact:
                continue
            # 只在「胶水层的 include 根」里找忽略大小写的匹配：
            # 否则仓库里任何同名文件（比如 libadrenotools/include/...）都会被当成
            # "大小写不匹配"，那是误报。
            hits = [p for p in exact
                    if p.lower().endswith("/" + inc.lower())
                    and any(p.startswith(r + "/") for r in glue_roots)]
            if not hits:
                continue        # 标准头 / Windows-only / 第三方 / NDK 头，不归这里管
            key = (inc, hits[0])
            problems += 1
            if key in seen:
                continue
            seen.add(key)
            print(f'大小写不匹配（尖括号）：<{inc}>  ←  {f}')
            print(f"    实际文件: {', '.join(hits)}")

    if problems:
        print(f"\n共 {problems} 处（{len(seen)} 个不同文件）。Linux 上会 fatal error。", file=sys.stderr)
        return 1
    print("include 大小写检查：干净")
    return 0


if __name__ == "__main__":
    sys.exit(main())
