# tools/custom —— 定制版补丁（让 master 保持「上游镜像」，Sync fork 零冲突）

## 分支模型（只用一个分支就够）

```
master = 上游镜像 + 新增文件 + tools/custom/* + workflow      ← 唯一需要维护的分支
         · Sync fork 随时点，零冲突（我们从不改上游文件）
         · 出包：Actions → Build ARMSX3 (custom Android) → Run workflow（**手动**）
         · CI 在 checkout 之后自己打那 16 处钩子
         · 这个 workflow **故意没有任何 push 触发**：否则 Sync fork / 合上游
           产生的提交都会烧一次 1~2 小时的构建
```

`custom-dimg-android` 只是当初做移植时的工作分支（内容 = master + 已打好的钩子），
留着可以本地对照，**不再需要**：任何改动都直接做在 master 上，本地验证时跑一次脚本即可。

> 注意：从 master 本地编译前必须 `python3 tools/custom/apply_custom_hooks.py`
> （否则得到的是没有授权的原版）；CI 自动做这一步。

## 为什么这么做

定制版对上游代码的改动只有两类：

| 类型 | 例子 | 会不会和上游冲突 |
|---|---|---|
| **新增文件** | `rpcs3/Crypto/dimg*`、`android/src/dimg_jni.cpp`、`com/armsx2/dimg/*`、`ui/license/LicenseScreen.kt`、workflow | **不会**（上游不会去写这些路径） |
| **对上游文件的少量钩子** | `Loader/ISO.cpp` 的打开点、`Emu/CMakeLists.txt` 的源列表、`native-lib.cpp` 的 dlsym 表、`RPCSX.kt` 的 external fun、`AppRoute/AppNavigation/NavigationDrawer/AppTab/MainActivityRuntime` 的入口、`proguard-rules.pro` 的 keep | **会**（这些正是上游天天改的文件） |

所以第二类不再写进提交，而是交给 **`apply_custom_hooks.py` 在构建时打**（幂等）：

```
master          = 上游镜像 + 新增文件 + tools/custom/* + workflow   →  Sync fork 一键、零冲突
custom-dimg-android = master + 已经打好的钩子（本地开发 / diff 基准用）
CI              = checkout 任意分支 → apply_custom_hooks.py → 编译 → 打包
```

`python3 tools/custom/apply_custom_hooks.py` 在当前树上跑一遍，结果与
`custom-dimg-android` 分支**逐字节相同**（已用一次性 worktree 验证）。

## 用法

```bash
python3 tools/custom/apply_custom_hooks.py           # 打钩子（已打过的跳过）
python3 tools/custom/apply_custom_hooks.py --check   # 只检查；缺任何一条就非零退出
python3 tools/custom/apply_custom_hooks.py --list    # 列出 16 条钩子及其作用
```

CI 里（`.github/workflows/build-android-custom.yml`）在 checkout 之后立刻调用它，所以：
**从 master 构建**和**从定制分支构建**走的是同一条路，产物一样。

## 上游改了被钩的代码怎么办

脚本会**明确报错**并停在那里（不会静默出一个没有授权的包）：

```
错误：钩子 'iso-open' 打不上 —— 锚点出现 0 次（期望 1 次）：ISO.cpp：第一个 iso_file 构造
  上游多半重构了这几行。请对照 tools/custom/README.md 修一下锚点。
```

处理流程：

1. 打开脚本，找到报错里点名的钩子（`HOOKS` 列表里 `name` 字段），看它的锚点常量；
2. 到上游新代码里找到对应位置（**行为**不变、只是行文/函数名变了）；
3. 更新锚点常量（`ISO_CTOR1_OLD` / `NATIVE_DLSYM_ANCHOR` / `GATE_CALL_ANCHOR` …）；
4. 重新生成定制分支并验证：

```bash
git checkout custom-dimg-android && git merge origin/master
python3 tools/custom/apply_custom_hooks.py --check     # 应该先报缺
# 改完锚点后：
rm -rf /tmp/ax3-verify && git worktree add -q --detach /tmp/ax3-verify <base>
# ……（脚本自带的一次性验证方法见本节末尾）
```

5. `bash 知识库/原始资料/tools/android_port/run_selftest.sh` 必须仍然 **ALL PASS**（它直接编
   `dimg.cpp` 的 Android 分支，验证加密链路没被上游改动打散）。

## 一次性验证脚本（改完锚点必跑）

```bash
cd <ARMSX3z>
BASE=$(git merge-base HEAD origin/master)
rm -rf /tmp/ax3-verify
git worktree add -q --detach /tmp/ax3-verify "$BASE"
for f in $(git diff --name-only --diff-filter=A "$BASE" HEAD); do
  mkdir -p "/tmp/ax3-verify/$(dirname "$f")"; cp "$f" "/tmp/ax3-verify/$f";
done
mkdir -p /tmp/ax3-verify/tools/custom && cp tools/custom/apply_custom_hooks.py /tmp/ax3-verify/tools/custom/
(cd /tmp/ax3-verify && python3 tools/custom/apply_custom_hooks.py)
# 期望：逐文件与当前分支一致（没有输出 = 一致）
for f in $(git diff --name-only "$BASE" HEAD); do
  diff -q "/tmp/ax3-verify/$f" "$f" >/dev/null || echo "  差异 $f"
done
git worktree remove --force /tmp/ax3-verify
```

## 关于 OAEP 参数（E6 的常见来源）

license 里的密钥盒子是 RSA-OAEP。不同厂商的 TEE / StrongBox 对「摘要 + MGF1 摘要」的支持不一样，
所以客户端（`DimgKeystore.decrypt`）按 **sha256/mgf1-sha256 → sha256/mgf1-sha1 → sha1/mgf1-sha1**
依次尝试，哪个成功用哪个；三种参数都失败时，把 Cipher 抛的原文显示在授权页（"底层原因：…"）。

**真机实证（2026-10-30，Snapdragon）**：SHA-256 + **MGF1-SHA256** 被 TEE 拒绝（E6），
SHA-256 + **MGF1-SHA1** 成功。所以 `iso_pack.py` 现在按 profile 决定默认
（`android → sha256-mgf1sha1`，win/deck → `sha256`）。

发行侧可以任选一种签：

```bash
iso_pack.py activate --profile android --request req.bin --oaep sha1 --out license.bin
```

## 附带的自检

```bash
python3 tools/custom/check_include_case.py
```
扫全仓 `#include "..."`（以及胶水层 `app/src/main/cpp` 里的 `#include <...>`），只报
「精确大小写不存在、但忽略大小写存在」的那种 —— 也就是 **macOS 上能编、Linux CI 上
fatal error** 的坑（上游作者在 macOS 开发，APFS 大小写不敏感）。NDK/bionic 提供的头
（`elf.h`、`jni.h`、`GLES2/…`）在 `NDK_HEADERS` 白名单里，不会误报。

尖括号那条检查只在「胶水层自己的 include 根」里比对，并且整个跳过 CI 才 clone 的
`libadrenotools/`、`librashader/`——它们有自己的 include 路径，否则会把
`<adrenotools/driver.h>` 这种正常的 include 误报成大小写问题（第一次上线就误报了 4 个）。
CI 的 `Sanity check custom pieces` 里也会跑它。

目前 `tools/custom/shim/` 里有两个只为这种事存在的转发头：

| 文件 | 挡的是 |
|---|---|
| `Config.h` | `RSX/VK/FrameGen/FrameGenTypes.h` 里移植残留的 `#include "Config.h"`（本仓库没有这个头） |
| `Emu/system.h` | `RSX/VK/vkutils/sync.cpp` 里写成小写的 `#include "Emu/system.h"`（真实文件是 `Emu/System.h`） |

## 钩子清单（16 条）

| name | 文件 | 作用 |
|---|---|---|
| `iso-open` | `rpcs3/Loader/ISO.cpp` | 两个 `iso_file` 构造改走 `dimg::open_maybe_locked`（+ include） |
| `emu-cmake` | `rpcs3/Emu/CMakeLists.txt` | `dimg.cpp` / `dimg_android.cpp` 编进 `rpcs3_emu` |
| `android-cmake` | `android/CMakeLists.txt` | `dimg_jni.cpp` 编进 core |
| `native-lib` | `android/.../cpp/native-lib.cpp` | `RPCSXApi` 七个字段 + dlsym + JNI 包装 |
| `rpcsx-kt` | `net/rpcsx/RPCSX.kt` | 七个 `external fun` |
| `bridge-kt` | `com/armsx3/Rpcs3Bridge.kt` | 启动时 `DimgKeystore.attach` + `installDimgBridge` |
| `route-kt` | `navigation/AppRoute.kt` | `AppRoute.License` |
| `nav-kt` | `navigation/AppNavigation.kt` | 路由 → `LicenseScreen` |
| `drawer-kt` | `navigation/NavigationDrawer.kt` | 抽屉入口 + 高亮判定 |
| `apptab-kt` | `ui/settings/AppTab.kt` | 设置页入口行（含「已激活/未激活」） |
| `gate-kt` | `runtime/MainActivityRuntime.kt` | 开游戏前的授权闸门 `gateDimg()` |
| `discord-aar` | `android/.../app/build.gradle.kts` | 没有 `libs/discord_partner_sdk.aar` 时**不声明**这个依赖，否则 `merge*NativeLibs` 阶段 transform 不存在的 aar 直接失败 |
| `savers-gl-case` | `android/.../savers/skyrocket/shockwave.cpp` | `<gl/gl.h>` → `<GL/gl.h>`：仓库里是 `savers/compat/GL/gl.h`，macOS 大小写不敏感能编，Linux fatal error |
| `discord-sdk` | `android/.../app/src/main/cpp/CMakeLists.txt` | 给 `armsx2_discord` 的 aarch64 条件加 `EXISTS` 判断：Discord Social SDK 是专有的、不随仓库分发，git 上拉下来的树里没有 `libdiscord_partner_sdk.so`，否则 ninja 报 "missing and no known rule to make it" |
| `framegen-config` | `rpcs3/Emu/CMakeLists.txt` | 把 `tools/custom/shim`（空 `Config.h`）以最低优先级加进 `rpcs3_emu`：上游 FrameGen 头里留了 PCSX2 的 `#include "Config.h"`，本仓库没有这个头 |
| `proguard` | `app/proguard-rules.pro` | `-keep class com.armsx2.dimg.DimgKeystore` |
