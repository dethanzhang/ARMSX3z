// 定制版：给上游 RSX/VK/FrameGen/FrameGenTypes.h 里那句 `#include "Config.h"` 的替身。
//
// 这行是从 Eden / PCSX2 移植 FrameGen 时留下的：PCSX2 的 Config.h 提供 GSConfig /
// EmuConfig，而本仓库已经把那些设置改接到 rpcs3 的 g_cfg（见 FrameGenConfig.h，
// 那里明说"两套词表只在这一处交汇"）。整个 FrameGen 目录里再没有任何地方用到
// GSConfig / EmuConfig —— 只有注释提到它们。
//
// 所以这里给一个空头，由 tools/custom/apply_custom_hooks.py 在构建时把
// `tools/custom/shim` 目录以**最低优先级**加进 rpcs3_emu 的 include 路径：
//   * 不改上游任何文件（Sync fork 依然零冲突）；
//   * 放在 include 顺序最后，所以 `Utilities/Config.cpp` 之类的 `#include "Config.h"`
//     仍旧先命中它自己所在目录的 Utilities/Config.h（quoted include 先查本目录）；
//   * 上游哪天自己补上真正的 Config.h，把这条 hook 删掉即可。
//
// CI 首次构建就是因为缺这个头而死在 RSX/VK/FrameGen/FrameGenPacer.cpp。
#pragma once
