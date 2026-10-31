#include <jni.h>

#include <string>
#include <vector>

#include <android/log.h>

#include "Crypto/dimg.h"
#include "Crypto/dimg_android.h"

// Android 定制版（ARMSX3）的授权接口。
//
// 和桌面版的关系：加密格式、申请码/license 格式、`iso_pack.py` 全部沿用；差的只是
//   ① 平台档 tag[9] = 2（所以拿 Windows / Deck 的盘打不开，反之亦然）
//   ② 设备密钥在 AndroidKeyStore 里（不可导出；卸载即销毁）
//   ③ 没有命令行，激活/诊断/重置走这里的几个 JNI 函数
//
// 这些函数由 Kotlin 的 net.rpcsx.RPCSX（glue 层 dlopen 本 .so 后 dlsym）调到，
// 具体见 android/armsx3-ui/app/src/main/cpp/native-lib.cpp 的同名包装。

#define DIMG_LOG(...) __android_log_print(ANDROID_LOG_INFO, "ARMSX3-DIMG", __VA_ARGS__)

namespace
{
	jstring utf8(JNIEnv* env, const std::string& text)
	{
		return env->NewStringUTF(text.c_str());
	}

	jobjectArray pair(JNIEnv* env, const std::string& a, const std::string& b)
	{
		jclass cls = env->FindClass("java/lang/String");
		jobjectArray arr = env->NewObjectArray(2, cls, nullptr);
		env->DeleteLocalRef(cls);
		env->SetObjectArrayElement(arr, 0, utf8(env, a));
		env->SetObjectArrayElement(arr, 1, utf8(env, b));
		return arr;
	}

	std::string from_jstring(JNIEnv* env, jstring s)
	{
		if (!s)
		{
			return {};
		}
		const char* chars = env->GetStringUTFChars(s, nullptr);
		std::string out = chars ? chars : "";
		if (chars)
		{
			env->ReleaseStringUTFChars(s, chars);
		}
		return out;
	}
}

// Keystore 桥装上以后，dimg 才能拿到设备公钥 / 解 OAEP / 验签。
// 必须从「起于 Java 的线程」调用（FindClass 在原生 attach 的线程上看不到 app 类），
// 所以 Rpcs3Bridge.initializeOnce 里和 SAF 桥挨着调，见 rpcsx-android.cpp 的同款说明。
extern "C" void _rpcsx_installDimgBridge(JNIEnv* env)
{
	if (!env)
	{
		return;
	}

	dimg_android::install(env);
	DIMG_LOG("keystore bridge installed=%d", dimg_android::available() ? 1 : 0);
}

// 生成申请码：[code, error]
extern "C" jobjectArray _rpcsx_dimgRequest(JNIEnv* env)
{
	std::string err;
	const std::string code = dimg::activation_code(err);

	if (code.empty())
	{
		DIMG_LOG("request failed: %s", err.c_str());
	}
	else
	{
		DIMG_LOG("request generated (%zu chars)", code.size());
	}
	return pair(env, code, err);
}

// 当前激活状态：[state(ACTIVE/INACTIVE), reason]
extern "C" jobjectArray _rpcsx_dimgStatus(JNIEnv* env)
{
	std::string reason;
	const std::string state = dimg::license_summary(reason);
	return pair(env, state, reason);
}

// 开游戏前探镜像：[status(int as string), detail, platform]
extern "C" jobjectArray _rpcsx_dimgProbe(JNIEnv* env, jstring path)
{
	const std::string p = from_jstring(env, path);
	const dimg::probe_result res = dimg::probe_image(p);

	jclass cls = env->FindClass("java/lang/String");
	jobjectArray arr = env->NewObjectArray(3, cls, nullptr);
	env->DeleteLocalRef(cls);
	env->SetObjectArrayElement(arr, 0, utf8(env, std::to_string(static_cast<int>(res.status))));
	env->SetObjectArrayElement(arr, 1, utf8(env, res.detail));
	env->SetObjectArrayElement(arr, 2, utf8(env, res.platform));
	return arr;
}

// 粘激活码导入（返回 "" = 成功，否则是错误说明）
extern "C" jstring _rpcsx_dimgImportText(JNIEnv* env, jstring text)
{
	std::string err;
	if (!dimg::import_license_text(from_jstring(env, text), err))
	{
		DIMG_LOG("import(text) failed: %s", err.c_str());
		return utf8(env, err.empty() ? "导入失败" : err);
	}
	return utf8(env, "");
}

// 从文件导入（license.bin）；返回 "" = 成功
extern "C" jstring _rpcsx_dimgImportFile(JNIEnv* env, jstring path)
{
	std::string err;
	if (!dimg::import_license_file(from_jstring(env, path), err))
	{
		DIMG_LOG("import(file) failed: %s", err.c_str());
		return utf8(env, err.empty() ? "导入失败" : err);
	}
	return utf8(env, "");
}

// 重置设备密钥（会让已发出的 license 失效）：同时清空授权 store
extern "C" jstring _rpcsx_dimgReset(JNIEnv* env)
{
	std::string note;
	dimg::reset_device_key(note);
	DIMG_LOG("reset: %s", note.c_str());
	return utf8(env, note);
}

// 已安装授权列表：每个元素是一行，字段用 \t 分隔：
//   licid \t platform \t recipient \t hwid \t opens(1/0) \t tag_sha256 \t file \t error
// （不用 \n 拼多行：一个 license 一个数组元素，Kotlin 侧直接遍历）
extern "C" jobjectArray _rpcsx_dimgLicenses(JNIEnv* env)
{
	const std::vector<dimg::license_entry> entries = dimg::list_licenses();

	jclass cls = env->FindClass("java/lang/String");
	jobjectArray arr = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
	env->DeleteLocalRef(cls);

	jsize index = 0;
	for (const dimg::license_entry& e : entries)
	{
		const std::string line = e.licid + "\t" + e.platform + "\t" + e.recipient + "\t" + e.hwid + "\t"
			+ (e.opens_here ? "1" : "0") + "\t" + e.tag_sha256 + "\t" + e.file + "\t" + e.error;
		jstring item = utf8(env, line);
		env->SetObjectArrayElement(arr, index++, item);
		env->DeleteLocalRef(item);
	}

	return arr;
}

// 删除一份授权（licid = sha256(tag 4096B)[:16]）；返回 "" = 成功，否则是错误说明
extern "C" jstring _rpcsx_dimgDeleteLicense(JNIEnv* env, jstring licid)
{
	std::string err;
	if (!dimg::delete_license(from_jstring(env, licid), err))
	{
		DIMG_LOG("delete license failed: %s", err.c_str());
		return utf8(env, err.empty() ? "删除失败" : err);
	}
	return utf8(env, "");
}
