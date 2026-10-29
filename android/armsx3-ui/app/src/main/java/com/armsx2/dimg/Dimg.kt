package com.armsx2.dimg

import android.util.Log
import com.armsx2.storage.ContentUri
import net.rpcsx.RPCSX

/**
 * 定制版（ARMSX3）的授权门面：把 core 那几个 JNI 函数包成 Kotlin 能用的形状，并对探测结果做缓存。
 *
 * 语义参照桌面版（rpcs3.exe --activate / --diag + dimg.cpp）：
 *   ① 生成申请码 → 发给作者 → 作者回 license.bin → 导入
 *   ② 盘是整盘 AES-256-CTR 加密的，只有拿到本机 license 才能解出 K_iso；
 *      三个平台档（Windows / Steam Deck / Android）的盘互不相通（tag[9]）。
 *
 * 没有授权时不会崩、也不会静默黑屏：boot 前的闸门（MainActivityRuntime.launchGame）会拦下并
 * 跳到授权页。
 */
object Dimg {
    private const val TAG = "ARMSX3-DIMG"

    /** 与 dimg::probe_status 对齐 */
    const val OK = 0
    const val NEED_LICENSE = 1
    const val PLATFORM_MISMATCH = 2
    const val LICENSE_INVALID = 3
    const val UNREADABLE = 4

    data class Probe(val status: Int, val detail: String, val platform: String) {
        val pass: Boolean get() = status == OK
    }

    data class Status(val active: Boolean, val reason: String)

    private val cache = HashMap<String, Probe>()

    /** core 是不是定制版（老核心 / 上游 APK 没有 dimg 接口） */
    fun supported(): Boolean =
        runCatching { RPCSX.instance.dimgStatus() != null }.getOrDefault(false)

    /** 探一张盘。null = 本核心不支持授权（当作普通盘放行）。 */
    fun probe(rawPath: String): Probe? {
        if (!supported()) return null
        cache[rawPath]?.let { return it }

        val result = runCatching {
            val path = runCatching { ContentUri.bootPathFor(rawPath) }.getOrDefault(rawPath)
            val arr = RPCSX.instance.dimgProbe(path) ?: return@runCatching null
            Probe(
                status = arr.getOrNull(0)?.toIntOrNull() ?: UNREADABLE,
                detail = arr.getOrNull(1).orEmpty(),
                platform = arr.getOrNull(2).orEmpty(),
            )
        }.onFailure { Log.w(TAG, "probe failed: ${it.message}") }.getOrNull()

        if (result != null) cache[rawPath] = result
        return result
    }

    /** 允许重试：导入/重置之后必须把缓存清掉，否则闸门还按旧结论拦人。 */
    fun invalidate() = cache.clear()

    fun status(): Status = runCatching {
        val arr = RPCSX.instance.dimgStatus() ?: return@runCatching Status(false, "本版本不支持授权")
        Status(
            active = arr.getOrNull(0) == "ACTIVE",
            reason = arr.getOrNull(1).orEmpty(),
        )
    }.getOrElse { Status(false, "读取状态失败：${it.message}") }

    /** 生成申请码。返回 (激活码, 错误说明)。 */
    fun requestCode(): Pair<String, String> = runCatching {
        val arr = RPCSX.instance.dimgRequest() ?: return@runCatching "" to "本版本不支持授权"
        arr.getOrNull(0).orEmpty() to arr.getOrNull(1).orEmpty()
    }.getOrElse { "" to "生成失败：${it.message}" }

    /** 粘激活码导入；返回 "" = 成功。 */
    fun importText(text: String): String {
        invalidate()
        return runCatching { RPCSX.instance.dimgImportText(text.trim()) ?: "本版本不支持授权" }
            .getOrElse { "导入失败：${it.message}" }
    }

    /** 从 license.bin 导入（content:// 会先换成 core 能开的路径）；返回 "" = 成功。 */
    fun importFile(rawPath: String): String {
        invalidate()
        return runCatching {
            val path = runCatching { ContentUri.bootPathFor(rawPath) }.getOrDefault(rawPath)
            RPCSX.instance.dimgImportFile(path) ?: "本版本不支持授权"
        }.getOrElse { "导入失败：${it.message}" }
    }

    /** 重置设备密钥（已签发的 license 全部失效）。返回处理说明。 */
    fun reset(): String {
        invalidate()
        return runCatching { RPCSX.instance.dimgReset() ?: "本版本不支持授权" }
            .getOrElse { "重置失败：${it.message}" }
    }

    /** 申请码文件路径（core 写的那份，出包侧也能直接读） */
    fun requestFilePath(): String = RPCSX.rootDirectory.trimEnd('/') + "/config/dimg/request.bin"

    fun licenseFilePath(): String = RPCSX.rootDirectory.trimEnd('/') + "/config/dimg/license.bin"
}
