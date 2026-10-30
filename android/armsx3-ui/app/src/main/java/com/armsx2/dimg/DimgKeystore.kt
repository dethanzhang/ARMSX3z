package com.armsx2.dimg

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.Keep
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * 定制版（ARMSX3）授权用的设备密钥与验签，全部经 AndroidKeyStore / JCA 完成。
 *
 * 为什么不用 native 生私钥：Android 上「不可导出」只有 Keystore 能给 —— StrongBox（独立安全芯片）
 * 或 TEE 里的密钥材料根本不出安全世界，本进程连私钥字节都拿不到。代价是 **卸载应用即销毁**：
 * 重装后旧 license 一律失效，要重新生成申请码。
 *
 * 这边的方法由 core 的 `_rpcsx_installDimgBridge` 缓存成 jmethodID 后调用（见
 * `rpcs3/Crypto/dimg_android.cpp`）。**改签名必须同步改那边**，否则 install 直接失败。
 * 所有方法都标 @Keep，R8 不许改名或删。
 */
@Keep
object DimgKeystore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "armsx3-dimg-machine-key-v1"

    private const val BOX_LEN = 256      // RSA-2048 密文
    private const val PLAIN_LEN = 48     // K_iso(32) + iv(16)

    @Volatile
    private var appContext: Context? = null

    /** 由 Rpcs3Bridge.initializeOnce 在本进程早期调用；只用来取包名/版本/签名做设备报告。 */
    @Keep
    @JvmStatic
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * 有没有设备密钥；create=true 时没有就建。
     * 返回 null 表示就绪，否则是给玩家看的中文错误。
     */
    @Keep
    @JvmStatic
    fun ensureKey(create: Boolean): String? {
        if (hasKey()) return null
        if (!create) return "本机还没有设备密钥"

        // StrongBox 优先（独立安全芯片），不支持就退 TEE；再不行只能报错（不退回软件密钥）
        val attempts = listOf(true, false)
        var last: String? = null

        for (strongBox in attempts) {
            try {
                val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setUserAuthenticationRequired(false)

                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setIsStrongBoxBacked(true)
                }

                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
                generator.initialize(builder.build())
                generator.generateKeyPair()

                if (hasKey()) return null
                last = "Keystore 生成了密钥却读不回来"
            } catch (e: StrongBoxUnavailableException) {
                last = "本机没有 StrongBox"
            } catch (t: Throwable) {
                last = "建密钥失败：${t.message ?: t.javaClass.simpleName}"
            }
        }

        return last ?: "建密钥失败"
    }

    /** 设备公钥模数（256B 大端）。申请码里带的就是这个。 */
    @Keep
    @JvmStatic
    fun modulus(): ByteArray? = runCatching {
        val certificate = keyStore().getCertificate(ALIAS) ?: return null
        val modulus = (certificate.publicKey as java.security.interfaces.RSAPublicKey).modulus
        val raw = modulus.toByteArray()               // BigInteger → 可能带前导 0x00
        val out = ByteArray(BOX_LEN)
        if (raw.size == BOX_LEN + 1 && raw[0] == 0.toByte()) {
            System.arraycopy(raw, 1, out, 0, BOX_LEN)
        } else if (raw.size <= BOX_LEN) {
            System.arraycopy(raw, 0, out, BOX_LEN - raw.size, raw.size)
        } else {
            return null
        }
        out
    }.getOrNull()

    /**
     * 设备私钥解不开时记下的原因（给状态页/logcat 看）。
     * TEE 不支持某个 OAEP 参数组合时，异常全被 Cipher 抛在 init/doFinal 里，
     * 不记下来就只剩一句没用的 "E6"。
     */
    @Volatile
    private var lastDecryptError: String? = null

    @Keep
    @JvmStatic
    fun lastError(): String? = lastDecryptError

    /**
     * license 里的密钥盒子是 RSA-OAEP（K_iso||iv，48B）。不同厂商的 TEE / StrongBox 对
     * 「摘要 + MGF1 摘要」的支持不一样：主流是 SHA-256/MGF1-SHA256（发行侧默认），但也有只肯
     * 做 MGF1-SHA1、甚至只做 SHA-1 的。所以按顺序试三种，第一个成功的就用。
     *
     * 三种都是同一份密文：MGF1-SHA1 那两个分支只对「发行侧用对应参数加密」的盒子才解得开，
     * 所以这里试的顺序不影响正确性 —— 解不开就是解不开，不会误解。
     */
    private val OAEP_SPECS: List<Pair<String, OAEPParameterSpec>> = listOf(
        "sha256/mgf1-sha256" to OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        ),
        "sha256/mgf1-sha1" to OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
        ),
        "sha1/mgf1-sha1" to OAEPParameterSpec(
            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
        ),
    )

    /** 解 256B → 48B（K_iso||iv）。换机 / 重装 / 换签名 / TEE 不支持该 OAEP 参数都在这失败。 */
    @Keep
    @JvmStatic
    fun decrypt(box: ByteArray): ByteArray? {
        if (box.size != BOX_LEN) {
            lastDecryptError = "密文长度 ${box.size}，应为 $BOX_LEN"
            return null
        }

        val key = runCatching { keyStore().getKey(ALIAS, null) as? java.security.PrivateKey }
            .getOrNull()
        if (key == null) {
            lastDecryptError = "取不到设备私钥（Keystore 里没有 ${ALIAS}）"
            return null
        }

        val failures = StringBuilder()
        for ((label, spec) in OAEP_SPECS) {
            val result = runCatching {
                val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, spec)
                cipher.doFinal(box)
            }
            val plain = result.getOrNull()
            if (plain != null && plain.size == PLAIN_LEN) {
                lastDecryptError = null
                return plain
            }
            val why = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "解出 ${plain?.size} 字节（应为 $PLAIN_LEN）"
            failures.append("[").append(label).append("] ").append(why).append("; ")
            android.util.Log.w("ARMSX3-DIMG", "OAEP $label failed: $why")
        }

        lastDecryptError = "三种 OAEP 参数都失败：$failures"
        return null
    }

    /** 删掉设备密钥。返回 null 表示删成功，否则是说明。 */
    @Keep
    @JvmStatic
    fun reset(): String? = runCatching {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) {
            ks.deleteEntry(ALIAS)
        }
        null
    }.getOrElse { "删除设备密钥失败：${it.message ?: it.javaClass.simpleName}" }

    /**
     * license 签名校验：ECDSA-P256，签名是 raw r||s（与 Windows/CNG、Deck/OpenSSL 同格式），
     * 输入是「已经算好的 SHA-256 摘要」⇒ 用 NONEwithECDSA，不能再让 JCA 哈希一遍。
     */
    @Keep
    @JvmStatic
    fun ecdsaVerify(pub65: ByteArray, hash: ByteArray, sig: ByteArray): Boolean {
        if (pub65.size != 65 || pub65[0] != 0x04.toByte() || hash.size != 32 || sig.size != 64) return false

        return runCatching {
            val x = BigInteger(1, pub65.copyOfRange(1, 33))
            val y = BigInteger(1, pub65.copyOfRange(33, 65))

            val params = java.security.AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec("secp256r1"))
                getParameterSpec(ECParameterSpec::class.java)
            }
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(ECPoint(x, y), params))

            val verifier = Signature.getInstance("NONEwithECDSA")
            verifier.initVerify(publicKey)
            verifier.update(hash)
            verifier.verify(rawToDer(sig))
        }.getOrDefault(false)
    }

    /** raw r||s(64B) → DER SEQUENCE{INTEGER r, INTEGER s}（每个 INTEGER 去前导零、需要时补 0x00） */
    private fun rawToDer(sig: ByteArray): ByteArray {
        val r = derInt(sig.copyOfRange(0, 32))
        val s = derInt(sig.copyOfRange(32, 64))
        val body = r + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun derInt(raw: ByteArray): ByteArray {
        var start = 0
        while (start < raw.size - 1 && raw[start] == 0.toByte()) start++
        val trimmed = raw.copyOfRange(start, raw.size)
        val needsPad = (trimmed[0].toInt() and 0x80) != 0
        val body = if (needsPad) byteArrayOf(0) + trimmed else trimmed
        return byteArrayOf(0x02, body.size.toByte()) + body
    }

    /** 设备指纹 8 字节：只用于出包侧对账（绑定靠 RSA 密钥本身，不靠它）。 */
    @Keep
    @JvmStatic
    fun hwid8(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val androidId = runCatching {
            appContext?.let { Settings.Secure.getString(it.contentResolver, Settings.Secure.ANDROID_ID) }
        }.getOrNull() ?: "-"
        val text = listOf(
            androidId,
            Build.FINGERPRINT,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            signingDigest()
        ).joinToString("|")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).copyOfRange(0, 8)
    }

    /** 申请码里带回去的机器报告（出包侧排障用）。 */
    @Keep
    @JvmStatic
    fun deviceInfo(): String {
        val context = appContext
        val version = runCatching {
            val info = context?.let { it.packageManager.getPackageInfo(it.packageName, 0) }
            "${info?.versionName ?: "?"} (${info?.longVersionCode ?: 0})"
        }.getOrDefault("?")

        val mem = runCatching {
            val kb = java.io.File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemTotal:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
            "%.1f GB".format(kb / 1024.0 / 1024.0)
        }.getOrDefault("?")

        val sb = runCatching {
            val ks = keyStore()
            val key = ks.getKey(ALIAS, null) ?: return@runCatching "无密钥"
            val info = ks.getKey(ALIAS, null)?.let {
                val factory = KeyFactory.getInstance(it.algorithm, ANDROID_KEYSTORE)
                factory.getKeySpec(it, KeyInfo::class.java)
            }
            when {
                info == null -> key.algorithm
                info.isInsideSecureHardware -> if (info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX) "StrongBox" else "TEE"
                else -> "软件（不安全）"
            }
        }.getOrDefault("无密钥")

        return buildString {
            appendLine("机型         : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("操作系统     : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("应用版本     : $version")
            appendLine("CPU          : ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
            appendLine("物理内存     : $mem")
            appendLine("安全硬件     : $sb")
            append("安装包签名   : ${signingDigest().take(16)}…")
        }
    }

    /** 安装包签名证书的 SHA-256（换签名 = 换设备身份，重签过的包会被 E6 拒） */
    private fun signingDigest(): String = runCatching {
        val context = appContext ?: return@runCatching "?"
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching "?"
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("?")
}
