// 定制版：上游 rpcs3/Emu/RSX/VK/vkutils/sync.cpp 里写的是
//     #include "Emu/system.h"
// 而真实文件名是 Emu/System.h（大小写不同）。上游开发在 macOS 上，APFS 大小写不敏感，
// 所以编得过；Linux CI 是大小写敏感的，直接 'Emu/system.h' file not found。
//
// 这里给一个小写名的转发头（由 apply_custom_hooks.py 把 tools/custom/shim 加进
// rpcs3_emu 的 include 路径），不改上游文件。
//
// 用尖括号形式，不走"先查本文件所在目录"：
//   * Linux：<Emu/System.h> 由 -I<repo>/rpcs3 命中真文件；
//   * macOS：同样是 -I 顺序命中真文件，不会 include 到本文件自己（那会因 #pragma once
//     变成空内容，Emu 未声明）。
#pragma once

#include <Emu/System.h>
