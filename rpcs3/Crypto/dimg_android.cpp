#include "stdafx.h"

#include "Crypto/dimg_android.h"

#ifdef __ANDROID__

#include <jni.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "util/logs.hpp"

LOG_CHANNEL(dimg_android_log, "DIMG.ANDROID");

// 与 android/src/saf_device.cpp 同一套做法：类在哪、方法签名是什么，都在这里定死；
// Kotlin 侧改签名必须同步改这里，否则 install() 会直接失败（日志里有原因）。

namespace dimg_android
{
	namespace
	{
		std::atomic<JavaVM*> g_vm{nullptr};
		jclass g_class = nullptr;

		jmethodID m_ensure = nullptr;    // ensureKey(Z)Ljava/lang/String;
		jmethodID m_modulus = nullptr;   // modulus()[B
		jmethodID m_decrypt = nullptr;   // decrypt([B)[B
		jmethodID m_reset = nullptr;     // reset()Ljava/lang/String;
		jmethodID m_verify = nullptr;    // ecdsaVerify([B[B)Z
		jmethodID m_hwid = nullptr;      // hwid8()[B
		jmethodID m_report = nullptr;    // deviceInfo()Ljava/lang/String;

		// GetEnv，必要时 attach 成 daemon 线程并在退出时 detach（同 saf_device 的 scoped_env）
		class scoped_env
		{
			JNIEnv* m_env = nullptr;
			bool m_detach = false;

		public:
			scoped_env()
			{
				JavaVM* vm = g_vm.load(std::memory_order_acquire);
				if (!vm)
				{
					return;
				}

				if (vm->GetEnv(reinterpret_cast<void**>(&m_env), JNI_VERSION_1_6) != JNI_OK)
				{
					if (vm->AttachCurrentThreadAsDaemon(&m_env, nullptr) != JNI_OK)
					{
						m_env = nullptr;
						return;
					}
					m_detach = true;
				}
			}

			~scoped_env()
			{
				if (m_env)
				{
					clear();
				}
				if (m_detach)
				{
					if (JavaVM* vm = g_vm.load(std::memory_order_acquire))
					{
						vm->DetachCurrentThread();
					}
				}
			}

			JNIEnv* get() const { return m_env; }
			explicit operator bool() const { return m_env != nullptr; }

			// Java 异常一律吃掉：这里没法把它翻译成 C++ 语义，只要当「失败」处理
			void clear() const
			{
				if (m_env && m_env->ExceptionCheck())
				{
					m_env->ExceptionDescribe();
					m_env->ExceptionClear();
				}
			}
		};

		bool ready()
		{
			return g_class && m_ensure && m_modulus && m_decrypt && m_verify && m_hwid;
		}

		// 把 Kotlin 返回的 String 取成 std::string（null → 空）
		std::string take_string(scoped_env& env, jstring s)
		{
			if (!s)
			{
				return {};
			}

			const char* utf = env.get()->GetStringUTFChars(s, nullptr);
			std::string out = utf ? utf : "";
			if (utf)
			{
				env.get()->ReleaseStringUTFChars(s, utf);
			}
			env.get()->DeleteLocalRef(s);
			return out;
		}

		// 把 byte[] 拷进 out（长度必须正好是 want，否则失败）
		bool take_bytes(scoped_env& env, jbyteArray arr, unsigned char* out, int want)
		{
			if (!arr)
			{
				return false;
			}

			const jsize len = env.get()->GetArrayLength(arr);
			if (len != want)
			{
				env.get()->DeleteLocalRef(arr);
				dimg_android_log.error("byte[] 长度 %d，期望 %d", int(len), want);
				return false;
			}

			env.get()->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(out));
			env.get()->DeleteLocalRef(arr);
			return true;
		}

		jbyteArray make_bytes(JNIEnv* env, const unsigned char* data, int len)
		{
			jbyteArray arr = env->NewByteArray(len);
			if (!arr)
			{
				return nullptr;
			}
			env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(data));
			return arr;
		}
	}

	void install(JNIEnv* env)
	{
		if (!env)
		{
			return;
		}

		if (g_vm.load(std::memory_order_acquire))
		{
			return;   // 幂等
		}

		jclass found = env->FindClass("com/armsx2/dimg/DimgKeystore");
		if (!found)
		{
			env->ExceptionClear();
			dimg_android_log.error("找不到 com/armsx2/dimg/DimgKeystore（R8 把它剥了？加 @Keep）");
			return;
		}

		jclass global = static_cast<jclass>(env->NewGlobalRef(found));
		env->DeleteLocalRef(found);

		if (!global)
		{
			return;
		}

		m_ensure  = env->GetStaticMethodID(global, "ensureKey", "(Z)Ljava/lang/String;");
		m_modulus = env->GetStaticMethodID(global, "modulus", "()[B");
		m_decrypt = env->GetStaticMethodID(global, "decrypt", "([B)[B");
		m_reset   = env->GetStaticMethodID(global, "reset", "()Ljava/lang/String;");
		m_verify  = env->GetStaticMethodID(global, "ecdsaVerify", "([B[B[B)Z");
		m_hwid    = env->GetStaticMethodID(global, "hwid8", "()[B");
		m_report  = env->GetStaticMethodID(global, "deviceInfo", "()Ljava/lang/String;");

		if (env->ExceptionCheck() || !m_ensure || !m_modulus || !m_decrypt || !m_reset || !m_verify || !m_hwid || !m_report)
		{
			env->ExceptionClear();
			dimg_android_log.error("DimgKeystore 方法签名不匹配（Kotlin 侧改签名了？）");
			env->DeleteGlobalRef(global);
			m_ensure = m_modulus = m_decrypt = m_reset = m_verify = m_hwid = m_report = nullptr;
			return;
		}

		g_class = global;

		JavaVM* vm = nullptr;
		env->GetJavaVM(&vm);
		g_vm.store(vm, std::memory_order_release);

		dimg_android_log.success("keystore bridge installed");
	}

	void uninstall()
	{
		if (!g_class)
		{
			return;
		}

		if (scoped_env env)
		{
			env.get()->DeleteGlobalRef(g_class);
		}

		g_class = nullptr;
		m_ensure = m_modulus = m_decrypt = m_reset = m_verify = m_hwid = m_report = nullptr;
		g_vm.store(nullptr, std::memory_order_release);
	}

	bool available()
	{
		return ready() && g_vm.load(std::memory_order_acquire) != nullptr;
	}

	bool keystore_ready(bool create, std::string& err)
	{
		if (!available())
		{
			err = "Keystore 桥未安装";
			return false;
		}

		scoped_env env;
		if (!env)
		{
			err = "取不到 JNIEnv";
			return false;
		}

		jstring msg = static_cast<jstring>(env.get()->CallStaticObjectMethod(g_class, m_ensure, static_cast<jboolean>(create ? JNI_TRUE : JNI_FALSE)));
		if (env.get()->ExceptionCheck())
		{
			env.clear();
			err = "Keystore 调用异常";
			return false;
		}

		const std::string text = take_string(env, msg);
		if (!text.empty())
		{
			err = text;
			return false;
		}
		return true;
	}

	bool keystore_modulus(unsigned char out[256])
	{
		if (!available())
		{
			return false;
		}

		scoped_env env;
		if (!env)
		{
			return false;
		}

		jbyteArray arr = static_cast<jbyteArray>(env.get()->CallStaticObjectMethod(g_class, m_modulus));
		if (env.get()->ExceptionCheck())
		{
			env.clear();
			return false;
		}
		return take_bytes(env, arr, out, 256);
	}

	bool keystore_decrypt(const unsigned char* in, unsigned char out[48])
	{
		if (!available())
		{
			return false;
		}

		scoped_env env;
		if (!env)
		{
			return false;
		}

		jbyteArray box = make_bytes(env.get(), in, 256);
		if (!box)
		{
			return false;
		}

		jbyteArray plain = static_cast<jbyteArray>(env.get()->CallStaticObjectMethod(g_class, m_decrypt, box));
		env.get()->DeleteLocalRef(box);

		if (env.get()->ExceptionCheck())
		{
			env.clear();
			return false;
		}
		return take_bytes(env, plain, out, 48);
	}

	bool keystore_reset(std::string& note)
	{
		if (!available())
		{
			note = "Keystore 桥未安装";
			return false;
		}

		scoped_env env;
		if (!env)
		{
			note = "取不到 JNIEnv";
			return false;
		}

		jstring msg = static_cast<jstring>(env.get()->CallStaticObjectMethod(g_class, m_reset));
		if (env.get()->ExceptionCheck())
		{
			env.clear();
			note = "Keystore 调用异常";
			return false;
		}

		const std::string text = take_string(env, msg);
		note = text.empty() ? "已删除设备密钥（重装后的 license 需要重新签发）" : text;
		return text.empty();
	}

	bool ecdsa_verify(const unsigned char pub[65], const unsigned char hash[32], const unsigned char sig[64])
	{
		if (!available())
		{
			return false;
		}

		scoped_env env;
		if (!env)
		{
			return false;
		}

		jbyteArray k = make_bytes(env.get(), pub, 65);
		jbyteArray h = make_bytes(env.get(), hash, 32);
		jbyteArray s = make_bytes(env.get(), sig, 64);
		if (!k || !h || !s)
		{
			if (k) env.get()->DeleteLocalRef(k);
			if (h) env.get()->DeleteLocalRef(h);
			if (s) env.get()->DeleteLocalRef(s);
			return false;
		}

		const jboolean ok = env.get()->CallStaticBooleanMethod(g_class, m_verify, k, h, s);
		env.get()->DeleteLocalRef(k);
		env.get()->DeleteLocalRef(h);
		env.get()->DeleteLocalRef(s);

		if (env.get()->ExceptionCheck())
		{
			env.clear();
			return false;
		}
		return ok == JNI_TRUE;
	}

	bool hwid8(unsigned char out[8])
	{
		if (!available())
		{
			return false;
		}

		scoped_env env;
		if (!env)
		{
			return false;
		}

		jbyteArray arr = static_cast<jbyteArray>(env.get()->CallStaticObjectMethod(g_class, m_hwid));
		if (env.get()->ExceptionCheck())
		{
			env.clear();
			return false;
		}
		return take_bytes(env, arr, out, 8);
	}

	std::string device_report()
	{
		if (!available())
		{
			return {};
		}

		scoped_env env;
		if (!env)
		{
			return {};
		}

		jstring s = static_cast<jstring>(env.get()->CallStaticObjectMethod(g_class, m_report));
		if (env.get()->ExceptionCheck())
		{
			env.clear();
			return {};
		}
		return take_string(env, s);
	}
}

#else

// 非 Android 构建：这些符号必须存在（dimg.cpp 的 Android 分支才不会链接失败），但永远用不到。
namespace dimg_android
{
	bool available() { return false; }
	bool keystore_ready(bool, std::string& err) { err = "not android"; return false; }
	bool keystore_modulus(unsigned char*) { return false; }
	bool keystore_decrypt(const unsigned char*, unsigned char*) { return false; }
	bool keystore_reset(std::string& note) { note = "not android"; return false; }
	bool ecdsa_verify(const unsigned char*, const unsigned char*, const unsigned char*) { return false; }
	bool hwid8(unsigned char*) { return false; }
	std::string device_report() { return {}; }
}

#endif
