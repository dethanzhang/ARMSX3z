package com.armsx2.dimg

import android.util.Log
import com.armsx2.storage.ContentUri
import net.rpcsx.RPCSX
import java.io.File

/**
 * 定制版（ARMSX3）的授权门面：把 core 那几个 JNI 函数包成 Kotlin 能用的形状，并对探测结果做缓存。
 *
 * 语义参照桌面版（rpcs3.exe --activate / --diag + dimg.cpp）：
 *   ① 生成申请码 → 发给作者 → 作者回一份 .lic → 导入
 *   ② 盘是整盘 AES-256-CTR 加密的，只有拿到本机 license 才能解出 K_iso；
 *      三个平台档（Windows / Steam Deck / Android）的盘互不相通（tag[9]）。
 *
 * P3 起是「多 license store」：每份授权单独存成
 *   <config>/dimg/licenses/<licid>.lic ，licid = sha256(盘上 4096B tag)[:16]（由内容算）。
 * - 打开某张盘时按盘上的 tag 选对应的那份 license，所以多份授权互不干扰；
 * - 导入是「一次一个文件 / 一次一串激活码」，装多少份都行；
 * - 旧的单槽 config/dimg/license.bin（以及 ISO 旁、cwd 里的那份）**不再读取**（reset 时顺带删掉）。
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

    /**
     * store 里的一行（来自 dimgLicenses()）。
     *
     * @param licid      16 位小写 hex，= sha256(4096B tag)[:16]
     * @param platform   "Android" 等
     * @param recipient  32 hex（申请码里的收件人）
     * @param hwid       16 hex（申请码里的设备指纹）
     * @param opens      本机私钥能不能解开这份
     * @param tagSha256  64 hex，对应盘上的 tag
     * @param file       完整路径，只用于排障，UI 不展示
     * @param error      opens=false 时的原因（成功时为空串）
     */
    data class License(
        val licid: String,
        val platform: String,
        val recipient: String,
        val hwid: String,
        val opens: Boolean,
        val tagSha256: String,
        val file: String,
        val error: String,
    )

    private val cache = HashMap<String, Probe>()

    /**
     * 缓存键：解析后的路径 + 长度 + mtime。
     *
     * 只按 rawPath 缓存会踩坑：同一个路径（比如 content:// 指向的临时文件、或者往同一位置换了张盘）
     * 换了内容之后结论会一直沿用旧值，闸门就按旧结论放行/拦人。文件不存在时退化成只用路径。
     */
    private fun cacheKey(resolved: String): String = runCatching {
        val f = File(resolved)
        if (f.exists()) "$resolved|${f.length()}|${f.lastModified()}" else resolved
    }.getOrDefault(resolved)

    /** core 是不是定制版（老核心 / 上游 APK 没有 dimg 接口） */
    fun supported(): Boolean =
        runCatching { RPCSX.instance.dimgStatus() != null }.getOrDefault(false)

    /** 探一张盘。null = 本核心不支持授权（当作普通盘放行）。 */
    fun probe(rawPath: String): Probe? {
        if (!supported()) return null

        // content:// 只解析一次：JNI 和缓存键都必须用同一个解析后的路径。
        val path = runCatching { ContentUri.bootPathFor(rawPath) }.getOrDefault(rawPath)
        val key = cacheKey(path)
        cache[key]?.let { return it }

        val result = runCatching {
            val arr = RPCSX.instance.dimgProbe(path) ?: return@runCatching null
            Probe(
                status = arr.getOrNull(0)?.toIntOrNull() ?: UNREADABLE,
                detail = arr.getOrNull(1).orEmpty(),
                platform = arr.getOrNull(2).orEmpty(),
            )
        }.onFailure { Log.w(TAG, "probe failed: ${it.message}") }.getOrNull()

        if (result != null) cache[key] = result
        return result
    }

    /** 允许重试：导入/导入文本/删除/重置之后必须把缓存清掉，否则闸门还按旧结论拦人。 */
    fun invalidate() = cache.clear()

    fun status(): Status = runCatching {
        val arr = RPCSX.instance.dimgStatus() ?: return@runCatching Status(false, "本版本不支持授权")
        Status(
            active = arr.getOrNull(0) == "ACTIVE",
            reason = arr.getOrNull(1).orEmpty(),
        )
    }.getOrElse { Status(false, "读取状态失败：${it.message}") }

    /**
     * 已安装的授权列表（store 里有多少份就有多少条）。
     *
     * JNI 每个元素一行，8 段用 \t 分隔：
     * licid / platform / recipient / hwid / opens(1/0) / tag_sha256 / file / error。
     * 用 limit = 8 切：error 里理论上可能有 tab，多出来的都归最后一段；末尾空 error 会保留成 ""。
     * 段数不足（core 版本不匹配 / 脏数据）的行直接丢掉，不让 UI 崩。
     */
    fun licenses(): List<License> = runCatching {
        val arr = RPCSX.instance.dimgLicenses() ?: return@runCatching emptyList<License>()
        arr.mapNotNull { line ->
            val f = line.split('\t', limit = 8)
            if (f.size < 8) return@mapNotNull null
            License(
                licid = f[0],
                platform = f[1],
                recipient = f[2],
                hwid = f[3],
                opens = f[4] == "1",
                tagSha256 = f[5],
                file = f[6],
                error = f[7],
            )
        }
    }.onFailure { Log.w(TAG, "licenses failed: ${it.message}") }.getOrElse { emptyList<License>() }

    /** 删一份授权（按 licid）。返回 "" = 成功，否则是中文错误说明。 */
    fun deleteLicense(licid: String): String {
        val result = runCatching {
            RPCSX.instance.dimgDeleteLicense(licid) ?: "本版本不支持授权"
        }.getOrElse { "删除失败：${it.message}" }
        if (result.isEmpty()) invalidate()
        return result
    }

    /** 生成申请码。返回 (激活码, 错误说明)。 */
    fun requestCode(): Pair<String, String> = runCatching {
        val arr = RPCSX.instance.dimgRequest() ?: return@runCatching "" to "本版本不支持授权"
        arr.getOrNull(0).orEmpty() to arr.getOrNull(1).orEmpty()
    }.getOrElse { "" to "生成失败：${it.message}" }

    /** 粘激活码导入；返回 "" = 成功。 */
    fun importText(text: String): String = runCatching {
        RPCSX.instance.dimgImportText(text.trim()) ?: "本版本不支持授权"
    }.getOrElse { "导入失败：${it.message}" }.also { invalidate() }

    /** 从 .lic 导入（content:// 会先换成 core 能开的路径）；返回 "" = 成功。 */
    fun importFile(rawPath: String): String = runCatching {
        val path = runCatching { ContentUri.bootPathFor(rawPath) }.getOrDefault(rawPath)
        RPCSX.instance.dimgImportFile(path) ?: "本版本不支持授权"
    }.getOrElse { "导入失败：${it.message}" }.also { invalidate() }

    /** 重置设备密钥（已签发的 license 全部失效，store 也会清空）。返回处理说明。 */
    fun reset(): String = runCatching {
        RPCSX.instance.dimgReset() ?: "本版本不支持授权"
    }.getOrElse { "重置失败：${it.message}" }.also { invalidate() }

    /** 申请码文件路径（core 写的那份，出包侧也能直接读） */
    fun requestFilePath(): String = RPCSX.rootDirectory.trimEnd('/') + "/config/dimg/request.bin"

    /** 多授权 store 的目录（每份一份 <licid>.lic）。 */
    fun licensesDirPath(): String = RPCSX.rootDirectory.trimEnd('/') + "/config/dimg/licenses"

    /**
     * 旧版单槽授权文件路径。P3 起 core 完全不读这里（Android 只认 licenses/ 下的 store），
     * 只留着给排障/迁移时对照；reset 会顺手把这份删掉。
     */
    @Deprecated("旧单槽 license.bin，P3 起不再读取；用 licenses()/licensesDirPath()")
    fun licenseFilePath(): String = RPCSX.rootDirectory.trimEnd('/') + "/config/dimg/license.bin"
}
