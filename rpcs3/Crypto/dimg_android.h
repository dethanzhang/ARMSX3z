#pragma once

// Android 侧的 dimg 后台：设备密钥 / 验签 / 设备指纹 都交给 AndroidKeyStore 与 JCA 完成。
//
// 为什么不自己在 native 里生成 RSA 私钥：Android 上「不可导出」这个性质只有 Keystore 能给
// （StrongBox 或 TEE 里的密钥材料根本不出安全世界），Windows 版用的是 CNG 的持久化键，
// Steam Deck 版用的是指纹派生的加密私钥文件 —— 三档各自用自己的最强方案。
//
// 私钥不进本进程、不进内存、不落文件；代价是**卸载应用即销毁**，重装后要重新激活。
//
// 这些函数由 `android/src/dimg_jni.cpp` 在 Java 线程里 install()（见那里的说明）；
// 没装上时全部返回 false（等价「未激活」），不会崩。

#include <string>

#ifdef __ANDROID__
#include <jni.h>
#endif

namespace dimg_android
{
#ifdef __ANDROID__
	// 缓存 com.armsx2.dimg.DimgKeystore 的类和静态方法。必须从「起于 Java 的线程」调用：
	// 原生线程 attach 上来时用的是系统 class loader，看不到 app 自己的类。
	void install(JNIEnv* env);
	void uninstall();
#endif

	// 桥是否已安装（Android 没装 = 授权界面还没初始化）
	bool available();

	// Keystore 里有没有设备密钥；create=true 时没有就建（StrongBox 优先，TEE 兜底）
	bool keystore_ready(bool create, std::string& err);

	// 设备公钥模数（256B 大端），用于申请码
	bool keystore_modulus(unsigned char out[256]);

	// RSA-OAEP(SHA-256) 解 256B 密文 → 48B（K_iso || iv）。
	// 换机 / 重装 / 换了 APK 签名 都会在这里失败（E6）。
	bool keystore_decrypt(const unsigned char* in, unsigned char out[48]);

	// 删掉设备密钥（--reset 语义）
	bool keystore_reset(std::string& note);

	// license 签名校验（ECDSA-P256，uncompressed 公钥 65B + raw r||s 64B；JCA 要 DER，转换在 Kotlin 侧做）
	bool ecdsa_verify(const unsigned char pub[65], const unsigned char hash[32], const unsigned char sig[64]);

	// 设备指纹 8 字节（ANDROID_ID + 机型 + 应用签名摘要 的 SHA-256 前 8B）
	bool hwid8(unsigned char out[8]);

	// 申请码里带回去的机器报告（机型 / 系统 / 内存 / 签名指纹 / 授权搜索路径）
	std::string device_report();
}
