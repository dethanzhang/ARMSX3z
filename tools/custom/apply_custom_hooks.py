#!/usr/bin/env python3
"""apply_custom_hooks.py —— 把定制版需要的「钩子」幂等地打进上游代码（构建时执行）。

为什么要有这个脚本
------------------
定制改动分成两类：

  1) **新增文件**（`rpcs3/Crypto/dimg*`、`android/src/dimg_jni.cpp`、`com/armsx2/dimg/*`、
     `ui/license/LicenseScreen.kt`、workflow）——加文件永远不会和上游冲突；
  2) **对上游文件的少量钩子**（ISO 打开点、CMake 源列表、JNI 透出、UI 入口、启动闸门、
     R8 keep）——**这才是 Sync fork 时冲突的来源**。

把第 2 类从「提交里的改动」改成「构建时执行的补丁」以后：

  * `master` 可以保持「上游镜像 + 我们的新增文件」⇒ `Sync fork` 一键、**零冲突**；
  * 上游哪天重构了被钩的那几行，这里会**明确报错**（文件/锚点/期望内容），
    而不是静默编出一个「没有授权」的包；
  * 补丁是幂等的：已经打过的分支（`custom-dimg-android`）再跑一次什么都不做。

用法
----
    python3 tools/custom/apply_custom_hooks.py            # 打补丁（缺什么补什么）
    python3 tools/custom/apply_custom_hooks.py --check    # 只检查；缺任何一条就非零退出
    python3 tools/custom/apply_custom_hooks.py --list     # 列出所有钩子

CI（.github/workflows/build-android-custom.yml）在 checkout 之后、configure 之前调用它，
所以「从 master 构建」和「从定制分支构建」走的是同一条路。
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]   # 仓库根（ARMSX3z/）

# ---------------------------------------------------------------------------
# 钩子定义
# ---------------------------------------------------------------------------
# 每条钩子：path（相对仓库根）/ marker（幂等判据：出现过就跳过）/ apply(text) -> text


@dataclass
class Hook:
    name: str
    path: str
    marker: str
    apply: callable          # (str) -> str，锚点找不到时抛 HookError
    why: str = ""


class HookError(RuntimeError):
    pass


def _replace_once(text: str, old: str, new: str, what: str) -> str:
    count = text.count(old)
    if count != 1:
        raise HookError(f"锚点出现 {count} 次（期望 1 次）：{what}\n--- 锚点 ---\n{old}")
    return text.replace(old, new, 1)


def _insert_before(text: str, anchor: str, payload: str, what: str) -> str:
    count = text.count(anchor)
    if count != 1:
        raise HookError(f"锚点出现 {count} 次（期望 1 次）：{what}\n--- 锚点 ---\n{anchor}")
    return text.replace(anchor, payload + anchor, 1)


def _insert_after(text: str, anchor: str, payload: str, what: str) -> str:
    count = text.count(anchor)
    if count != 1:
        raise HookError(f"锚点出现 {count} 次（期望 1 次）：{what}\n--- 锚点 ---\n{anchor}")
    return text.replace(anchor, anchor + payload, 1)


# ---------------------------------------------------------------- ISO 打开点
ISO_INCLUDE_PAYLOAD = '#include "Crypto/dimg.h"\n'

ISO_CTOR1_OLD = """iso_file::iso_file(const std::string& path, bs_t<fs::open_mode> mode)
{
\tm_file = fs::file(path, mode);
"""

ISO_CTOR1_NEW = """iso_file::iso_file(const std::string& path, bs_t<fs::open_mode> mode)
{
\t// 整盘加密的 .iso 在这里被透明包装成「按需解密」的 file（普通盘原样返回）
\tm_file = dimg::open_maybe_locked(path, mode);
"""

ISO_CTOR2_OLD = """iso_file::iso_file(const std::string& path, bs_t<fs::open_mode> mode, const iso_fs_node& node)
\t: m_meta(node.metadata)
{
\tm_file = fs::file(path, mode);
"""

ISO_CTOR2_NEW = """iso_file::iso_file(const std::string& path, bs_t<fs::open_mode> mode, const iso_fs_node& node)
\t: m_meta(node.metadata)
{
\tm_file = dimg::open_maybe_locked(path, mode);
"""


def _iso(text: str) -> str:
    text = _replace_once(text, '#include "Crypto/utils.h"\n',
                         '#include "Crypto/utils.h"\n' + ISO_INCLUDE_PAYLOAD,
                         "ISO.cpp：加 #include Crypto/dimg.h")
    text = _replace_once(text, ISO_CTOR1_OLD, ISO_CTOR1_NEW, "ISO.cpp：第一个 iso_file 构造")
    text = _replace_once(text, ISO_CTOR2_OLD, ISO_CTOR2_NEW, "ISO.cpp：第二个 iso_file 构造")
    return text


# ---------------------------------------------------------------- CMake
EMU_CMAKE_PAYLOAD = """    ../Crypto/dimg.cpp
    ../Crypto/dimg_android.cpp
"""

ANDROID_CMAKE_PAYLOAD = """    # 授权（定制版）：整盘加密镜像 + 离线激活的 JNI 面（核心实现在 rpcs3/Crypto/dimg*.cpp）
    src/dimg_jni.cpp
"""


# ---------------------------------------------------------------- native-lib.cpp
NATIVE_STRUCT_ANCHOR = """  bool (*patchSetEnabled)(std::string_view hash, std::string_view description,
                          std::string_view serial, std::string_view appVersion,
                          bool enabled);
"""

NATIVE_STRUCT_PAYLOAD = """  // 授权（定制版）：整盘加密镜像 + 离线激活。核心实现在 core 的 rpcs3/Crypto/dimg*.cpp，
  // 这里只是把 JNI 面透出去；旧核心没有这些符号时全部退化成“没有授权”。
  void (*installDimgBridge)(JNIEnv *env);
  jobjectArray (*dimgRequest)(JNIEnv *env);
  jobjectArray (*dimgStatus)(JNIEnv *env);
  jobjectArray (*dimgProbe)(JNIEnv *env, jstring path);
  jstring (*dimgImportText)(JNIEnv *env, jstring text);
  jstring (*dimgImportFile)(JNIEnv *env, jstring path);
  jstring (*dimgReset)(JNIEnv *env);
"""

NATIVE_DLSYM_ANCHOR = """    result.installStorageBridge = reinterpret_cast<decltype(installStorageBridge)>(dlsym(handle, "_rpcsx_installStorageBridge"));
"""

NATIVE_DLSYM_PAYLOAD = """    result.installDimgBridge = reinterpret_cast<decltype(installDimgBridge)>(dlsym(handle, "_rpcsx_installDimgBridge"));
    result.dimgRequest = reinterpret_cast<decltype(dimgRequest)>(dlsym(handle, "_rpcsx_dimgRequest"));
    result.dimgStatus = reinterpret_cast<decltype(dimgStatus)>(dlsym(handle, "_rpcsx_dimgStatus"));
    result.dimgProbe = reinterpret_cast<decltype(dimgProbe)>(dlsym(handle, "_rpcsx_dimgProbe"));
    result.dimgImportText = reinterpret_cast<decltype(dimgImportText)>(dlsym(handle, "_rpcsx_dimgImportText"));
    result.dimgImportFile = reinterpret_cast<decltype(dimgImportFile)>(dlsym(handle, "_rpcsx_dimgImportFile"));
    result.dimgReset = reinterpret_cast<decltype(dimgReset)>(dlsym(handle, "_rpcsx_dimgReset"));
"""

NATIVE_WRAPPERS = """
// ---------------------------------------------------------------- 授权（定制版）
//
// 与 SAF 桥同一个道理：installDimgBridge 必须起源于 Java 线程，所以从 Kotlin 的
// Rpcs3Bridge.initializeOnce 里调（紧接着 installStorageBridge）。

extern "C" JNIEXPORT void JNICALL
Java_net_rpcsx_RPCSX_installDimgBridge(JNIEnv *env, jobject) {
  if (rpcsxLib.installDimgBridge == nullptr) {
    return;
  }
  rpcsxLib.installDimgBridge(env);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_rpcsx_RPCSX_dimgRequest(JNIEnv *env, jobject) {
  if (rpcsxLib.dimgRequest == nullptr) return nullptr;
  return rpcsxLib.dimgRequest(env);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_rpcsx_RPCSX_dimgStatus(JNIEnv *env, jobject) {
  if (rpcsxLib.dimgStatus == nullptr) return nullptr;
  return rpcsxLib.dimgStatus(env);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_net_rpcsx_RPCSX_dimgProbe(JNIEnv *env, jobject, jstring path) {
  if (rpcsxLib.dimgProbe == nullptr || path == nullptr) return nullptr;
  return rpcsxLib.dimgProbe(env, path);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_dimgImportText(JNIEnv *env, jobject, jstring text) {
  if (rpcsxLib.dimgImportText == nullptr || text == nullptr) return wrap(env, "本版本不支持授权");
  return rpcsxLib.dimgImportText(env, text);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_dimgImportFile(JNIEnv *env, jobject, jstring path) {
  if (rpcsxLib.dimgImportFile == nullptr || path == nullptr) return wrap(env, "本版本不支持授权");
  return rpcsxLib.dimgImportFile(env, path);
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_rpcsx_RPCSX_dimgReset(JNIEnv *env, jobject) {
  if (rpcsxLib.dimgReset == nullptr) return wrap(env, "本版本不支持授权");
  return rpcsxLib.dimgReset(env);
}
"""


def _native_lib(text: str) -> str:
    text = _insert_after(text, NATIVE_STRUCT_ANCHOR, NATIVE_STRUCT_PAYLOAD, "native-lib.cpp：RPCSXApi 字段")
    text = _insert_after(text, NATIVE_DLSYM_ANCHOR, NATIVE_DLSYM_PAYLOAD, "native-lib.cpp：dlsym 表")
    if not text.endswith("\n"):
        text += "\n"
    return text + NATIVE_WRAPPERS


# ---------------------------------------------------------------- Kotlin 侧
RPCSX_ANCHOR = """    external fun installSoundBridge(): Boolean
"""

RPCSX_PAYLOAD = """
    // ---------------------------------------------------------------- 授权（定制版）
    //
    // 绑定 AndroidKeyStore 里的设备密钥给 dimg（整盘加密镜像的离线激活）。必须从 Java
    // 线程调：core 里要用 FindClass 找 com.armsx2.dimg.DimgKeystore。
    external fun installDimgBridge()

    /** 生成申请码：[激活码, 错误说明]（激活码为空即失败）。 */
    external fun dimgRequest(): Array<String>?

    /** 当前激活状态：[ACTIVE/INACTIVE, 中文说明]。 */
    external fun dimgStatus(): Array<String>?

    /** 开游戏前探镜像：[status, 说明, 盘的平台档]。status: 0=放行 1=要授权 2=档不符 3=授权无效。 */
    external fun dimgProbe(path: String): Array<String>?

    /** 粘激活码导入；返回 "" = 成功，否则是错误说明。 */
    external fun dimgImportText(text: String): String?

    /** 从 license.bin 导入；返回 "" = 成功。 */
    external fun dimgImportFile(path: String): String?

    /** 重置设备密钥（已发出的 license 会失效）。返回处理说明。 */
    external fun dimgReset(): String?
"""

BRIDGE_ANCHOR = """        runCatching { RPCSX.instance.installSoundBridge() }
"""

BRIDGE_PAYLOAD = """        // 授权（定制版）：先把 Context 交给 DimgKeystore（设备报告 / hwid 要用包名与签名），
        // 再把 Keystore 桥交给 dimg，之后 dimgProbe/dimgImport 才可用
        runCatching { com.armsx2.dimg.DimgKeystore.attach(context) }
        runCatching { RPCSX.instance.installDimgBridge() }
"""

ROUTE_ANCHOR = """    // About > Open-source licenses. Back returns to About.
    data object Licenses : AppRoute
"""

ROUTE_PAYLOAD = """    // 授权（定制版）：整盘加密镜像的离线激活。自定义构建才有东西可看；上游 APK 里显示“不支持”。
    // 从启动闸门（launchGame）和抽屉两处可达。
    data object License : AppRoute
"""

NAV_ANCHOR = """                AppRoute.Licenses -> LicensesScreen(onBack = { UiNavigator.navigate(AppRoute.About) })
"""

NAV_PAYLOAD = """                AppRoute.License -> com.armsx2.ui.license.LicenseScreen(onBack = UiNavigator::home)
"""

DRAWER_ITEM_ANCHOR = """        DrawerItem("news.title", "📰", AppRoute.News),
"""

DRAWER_ITEM_PAYLOAD = """        // 授权（定制版）：没有它加密的盘进不去，所以放在抽屉里，不和 About 混。
        DrawerItem("授权（定制版）", "🔑", AppRoute.License),
"""

DRAWER_SAME_ANCHOR = """    AppRoute.Licenses -> current is AppRoute.Licenses
"""

DRAWER_SAME_PAYLOAD = """    AppRoute.License -> current is AppRoute.License
"""

APPTAB_HEAD_ANCHOR = """fun AppTab() {
"""

APPTAB_HEAD_PAYLOAD = """    // 只算一次：JNI 调用会读 license.bin，别每次重组都走一遍
    val dimgActive = androidx.compose.runtime.remember {
        runCatching { com.armsx2.dimg.Dimg.supported() && com.armsx2.dimg.Dimg.status().active }.getOrDefault(false)
    }
"""

# 锚在「上一行 Surface 结束」这个位置：不能拿 Language 的 Surface( 当锚点，
# 那样会把它的 on 和 modifier 劈成两半（第一次写就是这么错的，diff 测试抓到了）
APPTAB_ROW_ANCHOR = """            onChange = { com.armsx2.packages.ShareInstalled.set(it) },
        )
"""

APPTAB_ROW_PAYLOAD = """        // 授权（定制版）：加密镜像的激活入口。放在设置里，玩家找不到时抽屉里也有一份。
        Surface(
            onClick = { UiNavigator.navigate(AppRoute.License) },
            modifier = Modifier.fillMaxWidth()
                .controllerFocusable("app.license", RoundedCornerShape(20.dp), onConfirm = { UiNavigator.navigate(AppRoute.License) }),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.46f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Text("🔑", fontSize = 22.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("授权（定制版）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (dimgActive) "已激活" else "未激活 · 点这里生成申请码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", color = MaterialTheme.colorScheme.primary, fontSize = 26.sp)
            }
        }
"""

GATE_CALL_ANCHOR = """            if (info?.locked == true) {
                println("@@ANDROID_LAUNCH_REJECT@@ reason=locked title=${info.title}")
                com.armsx2.LicencePrompt.ask(info)
                return
            }
"""

GATE_CALL_PAYLOAD = """            // 定制版：整盘加密镜像的授权闸门。没授权时 boot 只会得到 “Failed to open file”，
            // 所以在这里先探一下（普通盘返回 0，成本就是一次 4KB 读）。
            // 三种拦下的情况都跳到授权页，玩家在那里生成申请码 / 导入 license。
            if (!gateDimg(uri, info)) return
"""

GATE_FN_ANCHOR = """        /**
         * Set the active game path/URI and restart the VM. Used by
         * Library card taps — the URI comes from the user's persisted
         * ROMs tree (already has read perm) so emucore's FileSystem
         * routines can open it via the content:// JNI bridge.
         *
         * `info` is the GameInfo from the library scan when available;
         * stored on MainActivityRuntime.currentGame so the in-game overlay can show
         * cover art / extension badge / pre-resolved compat stars
         * without re-querying gamedb. Pass null when launching from a
         * path that doesn't have a GameInfo (Swap/Boot Disc file picker).
         */
"""

GATE_FN_PAYLOAD = """        /**
         * 定制版授权闸门：true = 放行。
         *
         * 拦下的三种情形（见 dimg::probe_status）都跳到授权页，顺便把原因说清楚：
         *   ① 加密盘 + 本机没有可用授权
         *   ② 这张盘是别的平台档（Windows / Steam Deck 的 ISO）
         *   ③ 授权与盘不匹配，或不是发给本机的（换机 / 重装 / 换了签名）
         *
         * 探测是同步的（读 4KB 标签 + 找 license.bin），结果按 uri 缓存，所以重复启动不再读盘。
         * 非定制版核心 / 普通未加密盘都直接放行。
         */
        private fun gateDimg(uri: String, info: GameInfo?): Boolean {
            val probe = runCatching { com.armsx2.dimg.Dimg.probe(uri) }.getOrNull() ?: return true
            if (probe.pass) return true

            println(
                "@@ANDROID_LAUNCH_REJECT@@ reason=dimg_${probe.status} platform=${probe.platform} " +
                    "title=${info?.title ?: "<direct>"} detail=${probe.detail}"
            )
            val activity = instance
            if (activity != null) {
                activity.runOnUiThread {
                    android.widget.Toast
                        .makeText(activity, probe.detail, android.widget.Toast.LENGTH_LONG)
                        .show()
                }
            }
            runCatching { com.armsx2.navigation.UiNavigator.navigate(com.armsx2.navigation.AppRoute.License) }
            return false
        }

"""

# Discord Social SDK 的 Gradle 依赖：aar 不存在时不要声明，否则 Gradle 在
# merge*NativeLibs 阶段会试图 transform 一个不存在的 aar 而失败
# （第七次构建：Could not resolve ... Failed to transform discord_partner_sdk.aar）。
# 与 CMake 那条（discord-sdk）是一对：一个管 native target，一个管 Gradle 依赖。
DISCORD_DEP_OLD = """    implementation(files("libs/discord_partner_sdk.aar"))"""

DISCORD_DEP_NEW = """    // 定制版：Discord Social SDK 是专有、不随仓库分发的；文件不在就不要声明这个依赖
    // （否则 merge*NativeLibs 阶段会 transform 一个不存在的 aar 而失败）。
    // 想启用 Discord：把 discord_partner_sdk.aar 放进 app/libs/（同上游 README）。
    if (file("libs/discord_partner_sdk.aar").exists()) {
        implementation(files("libs/discord_partner_sdk.aar"))
    }"""

# savers（XScreenSaver 移植）里唯一一处大小写错的 include：
#   savers/skyrocket/shockwave.cpp 写 <gl/gl.h>，仓库里实际是 savers/compat/GL/gl.h
# macOS 大小写不敏感能编，Linux 上 fatal error（第六次构建就死在这）。
SAVERS_GL_OLD = "#include <gl/gl.h>"
SAVERS_GL_NEW = "#include <GL/gl.h>"

# Discord Social SDK：专有、不随仓库分发（上游 README 自己说的），但胶水层的 CMakeLists
# 对 aarch64 无条件 add_library(armsx2_discord) + IMPORTED libdiscord_partner_sdk.so，
# 干净克隆里没这个 .so → ninja 报 "missing and no known rule to make it"。
# 给那个 if 加一个 EXISTS 条件即可；Kotlin 侧的 DiscordNative 本来就 catch 了 loadLibrary 失败。
# 注意 aarch64 这个 if 在文件里出现两次（第一次是 libadrenotools），锚点必须带下一行才唯一。
DISCORD_OLD = """if (CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    add_library(discord_partner_sdk SHARED IMPORTED)"""

DISCORD_NEW = """if (CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64" AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/discord_sdk/arm64-v8a/libdiscord_partner_sdk.so")
    add_library(discord_partner_sdk SHARED IMPORTED)"""

# 上游两处「只有名字/大小写不对」的 include，都用 tools/custom/shim 兜住：
#   * FrameGen/FrameGenTypes.h  →  "Config.h"（移植残留，见 shim/Config.h）
#   * VK/vkutils/sync.cpp       →  "Emu/system.h"（真实文件是 Emu/System.h，见 shim/Emu/system.h）
FRAMEGEN_CONFIG_PAYLOAD = """
# 定制版：上游 RSX/VK/FrameGen/FrameGenTypes.h 里 `#include "Config.h"` 是移植时的残留
# （PCSX2 的 Config.h；本仓库已把那些设置改接到 g_cfg）。给一个空头，且放在 include
# 顺序最后，避免改上游文件。说明见 tools/custom/shim/Config.h。
target_include_directories(rpcs3_emu PRIVATE "${CMAKE_SOURCE_DIR}/tools/custom/shim")
"""

PROGUARD_ANCHOR = """-keep class net.rpcsx.RPCSX { *; }
"""

PROGUARD_PAYLOAD = """
# 定制版授权：core 里（rpcs3/Crypto/dimg_android.cpp）用 FindClass + GetStaticMethodID
# 直接点名这个类和方法。R8 改名或删方法 = 授权链断掉（表现为“本机还没有设备密钥”）。
-keep class com.armsx2.dimg.DimgKeystore { *; }
"""

KT = "android/armsx3-ui/app/src/main/java/"

def _drawer(text: str) -> str:
    text = _insert_after(text, DRAWER_ITEM_ANCHOR, DRAWER_ITEM_PAYLOAD, "NavigationDrawer.kt：抽屉项")
    return _insert_after(text, DRAWER_SAME_ANCHOR, DRAWER_SAME_PAYLOAD, "NavigationDrawer.kt：高亮判定")



HOOKS: list[Hook] = [
    Hook("iso-open", "rpcs3/Loader/ISO.cpp", "dimg::open_maybe_locked", _iso,
         "整盘加密的 .iso 在 iso_file 里被透明包装"),
    Hook("emu-cmake", "rpcs3/Emu/CMakeLists.txt", "../Crypto/dimg.cpp",
         lambda t: _insert_after(t, "    ../Crypto/decrypt_binaries.cpp\n", EMU_CMAKE_PAYLOAD, "Emu/CMakeLists：加 dimg 源"),
         "把 dimg*.cpp 编进 rpcs3_emu"),
    Hook("android-cmake", "android/CMakeLists.txt", "src/dimg_jni.cpp",
         lambda t: _insert_after(t, "    src/remote_play_upnp.cpp\n", ANDROID_CMAKE_PAYLOAD, "android/CMakeLists：加 dimg_jni.cpp"),
         "把 JNI 面编进 core"),
    Hook("native-lib", "android/armsx3-ui/app/src/main/cpp/native-lib.cpp", "_rpcsx_dimgProbe", _native_lib,
         "glue 层透出 7 个 JNI 入口"),
    Hook("rpcsx-kt", KT + "net/rpcsx/RPCSX.kt", "external fun dimgRequest", lambda t: _insert_after(t, RPCSX_ANCHOR, RPCSX_PAYLOAD, "RPCSX.kt：external fun"),
         "Kotlin 侧声明 external fun"),
    Hook("bridge-kt", KT + "com/armsx3/Rpcs3Bridge.kt", "installDimgBridge()", lambda t: _insert_after(t, BRIDGE_ANCHOR, BRIDGE_PAYLOAD, "Rpcs3Bridge.kt：装桥"),
         "启动时装 Keystore 桥"),
    Hook("route-kt", KT + "com/armsx2/navigation/AppRoute.kt", "data object License :", lambda t: _insert_after(t, ROUTE_ANCHOR, ROUTE_PAYLOAD, "AppRoute.kt：License 路由"),
         "授权页路由"),
    Hook("nav-kt", KT + "com/armsx2/navigation/AppNavigation.kt", "AppRoute.License ->", lambda t: _insert_after(t, NAV_ANCHOR, NAV_PAYLOAD, "AppNavigation.kt：License 分支"),
         "路由 → 页面"),
    Hook("drawer-kt", KT + "com/armsx2/navigation/NavigationDrawer.kt", "AppRoute.License)", _drawer,
         "抽屉入口 + 高亮判定"),
    Hook("apptab-kt", KT + "com/armsx2/ui/settings/AppTab.kt", "dimgActive", lambda t: _insert_after(
        _insert_after(t, APPTAB_HEAD_ANCHOR, APPTAB_HEAD_PAYLOAD, "AppTab.kt：remember"), APPTAB_ROW_ANCHOR,
        APPTAB_ROW_PAYLOAD, "AppTab.kt：入口行"),
         "设置页入口"),
    Hook("gate-kt", KT + "com/armsx2/runtime/MainActivityRuntime.kt", "gateDimg(", lambda t: _insert_before(
        _insert_after(t, GATE_CALL_ANCHOR, GATE_CALL_PAYLOAD, "MainActivityRuntime.kt：闸门调用"), GATE_FN_ANCHOR,
        GATE_FN_PAYLOAD, "MainActivityRuntime.kt：闸门实现"),
         "开游戏前的授权闸门"),
    Hook("discord-aar", "android/armsx3-ui/app/build.gradle.kts",
         'if (file("libs/discord_partner_sdk.aar").exists())',
         lambda t: _replace_once(t, DISCORD_DEP_OLD, DISCORD_DEP_NEW,
                                 "build.gradle.kts：Discord aar 依赖"),
         "没有 Discord Social SDK 的 aar 时不要声明依赖（否则 mergeNativeLibs 阶段失败）"),
    Hook("savers-gl-case", "android/armsx3-ui/app/src/main/cpp/savers/skyrocket/shockwave.cpp",
         "#include <GL/gl.h>",
         lambda t: _replace_once(t, SAVERS_GL_OLD, SAVERS_GL_NEW,
                                 "shockwave.cpp：<gl/gl.h> → <GL/gl.h>"),
         "savers 里 <gl/gl.h> 的大小写（macOS 能编、Linux 报 file not found）"),
    Hook("discord-sdk", "android/armsx3-ui/app/src/main/cpp/CMakeLists.txt",
         "libdiscord_partner_sdk.so\")",
         lambda t: _replace_once(t, DISCORD_OLD, DISCORD_NEW,
                                 "app/src/main/cpp/CMakeLists.txt：Discord SDK 的 aarch64 条件"),
         "没有 Discord Social SDK（专有、不随仓库分发）时跳过 armsx2_discord target"),
    Hook("framegen-config", "rpcs3/Emu/CMakeLists.txt", "tools/custom/shim",
         lambda t: t.rstrip("\n") + "\n" + FRAMEGEN_CONFIG_PAYLOAD,
         "FrameGen 残留的 #include \"Config.h\" → 空替身（否则核心编不过）"),
    Hook("proguard", "android/armsx3-ui/app/proguard-rules.pro", "com.armsx2.dimg.DimgKeystore", lambda t: _insert_after(t, PROGUARD_ANCHOR, PROGUARD_PAYLOAD, "proguard：keep DimgKeystore"),
         "R8 不许删 Keystore 桥的目标类"),
]


# 新增文件：缺了就是没把定制文件带上（不是冲突，是操作失误）
REQUIRED_NEW_FILES = [
    "rpcs3/Crypto/dimg.cpp",
    "rpcs3/Crypto/dimg.h",
    "rpcs3/Crypto/dimg_android.cpp",
    "rpcs3/Crypto/dimg_android.h",
    "android/src/dimg_jni.cpp",
    KT + "com/armsx2/dimg/Dimg.kt",
    KT + "com/armsx2/dimg/DimgKeystore.kt",
    KT + "com/armsx2/ui/license/LicenseScreen.kt",
    "tools/custom/shim/Config.h",
    "tools/custom/shim/Emu/system.h",
]


def main() -> int:
    ap = argparse.ArgumentParser(description="幂等地把定制钩子打进上游代码")
    ap.add_argument("--check", action="store_true", help="只检查，不改文件")
    ap.add_argument("--list", action="store_true", help="列出所有钩子")
    ap.add_argument("--root", default=str(ROOT), help="仓库根（默认按脚本位置推断）")
    args = ap.parse_args()

    root = Path(args.root).resolve()

    if args.list:
        for h in HOOKS:
            print(f"{h.name:14s} {h.path}\n{'':14s} → {h.why}")
        return 0

    missing_files = [f for f in REQUIRED_NEW_FILES if not (root / f).is_file()]
    if missing_files:
        print("错误：缺少定制版新增文件（这不是冲突，是把定制文件漏了）：", file=sys.stderr)
        for f in missing_files:
            print(f"  - {f}", file=sys.stderr)
        return 2

    applied, already, broken = 0, 0, 0

    for hook in HOOKS:
        path = root / hook.path
        if not path.is_file():
            print(f"错误：{hook.path} 不存在（上游删了/改名了？钩子 '{hook.name}' 打不上）", file=sys.stderr)
            broken += 1
            continue

        text = path.read_text(encoding="utf-8")

        if hook.marker in text:
            already += 1
            continue

        if args.check:
            print(f"缺：{hook.name:14s} {hook.path}  （{hook.why}）", file=sys.stderr)
            broken += 1
            continue

        try:
            new_text = hook.apply(text)
        except HookError as e:
            print(f"错误：钩子 '{hook.name}' 打不上 —— {e}\n"
                  f"  上游多半重构了这几行。请对照 tools/custom/README.md 修一下锚点。", file=sys.stderr)
            broken += 1
            continue

        path.write_text(new_text, encoding="utf-8")
        print(f"OK   {hook.name:14s} {hook.path}")
        applied += 1

    print()
    print(f"打完：新打 {applied} 条 / 已存在 {already} 条 / 失败 {broken} 条")
    if broken:
        print("失败 —— 不要继续构建。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
