#pragma once

// Transparent whole-image decryption for locked .iso images + offline activation.
//
// Format (all big-endian, v4):
//   tag (4096 B at image offset 0x1000): "PS3LCK03" | ver=3 | flags | platform(tag[9]) | ... | recipient(16) | salt(32)
//   stream = AES-256-CTR(K_iso, iv), keystream forced to 0 inside [0x1000, 0x2000)
//   request.bin: "P3RQ" | ver=4 | flags | res | RSA-2048 modulus(256) | hwid(8) | crc16 | ("P3RX" bundle)
//   license.bin: "PS3LIC04" | ver=4 | flags | res | recipient(16) | hwid(8)
//                | RSA-OAEP(SHA-256) of K_iso(32)+iv(16) = 256 B | tag copy(4096) | ECDSA-P256 sig(64)
//
// 平台档（tag[9]，参与 K_iso 派生，三档互不相通）：
//   0 = Windows 定制 rpcs3.exe     设备密钥 = CNG 持久化键（TPM 优先）
//   1 = Steam Deck/Linux 定制版     设备密钥 = 指纹派生 KEK 加密的软件私钥文件
//   2 = Android/ARMSX3 定制版      设备密钥 = AndroidKeyStore（StrongBox/TEE，不可导出）
//
// The on-disk magics are kept for compatibility; in the binary they are XOR-masked (see MAGIC_XOR).

#include <string>
#include <vector>

#include "Utilities/File.h"

namespace dimg
{
	// Returns true if the file carries the image tag (checked at 0x1000). Takes a mutable ref (read_at is non-const).
	bool is_locked(fs::file& file);

	// Opens `path`. If the image is locked, returns a decrypting wrapper
	// (invalid file + log error when the activation data is missing/invalid).
	fs::file open_maybe_locked(const std::string& path, bs_t<fs::open_mode> mode);

	// Handles --activate / --diag / --reset-key. Returns true when handled.
	bool handle_command_line(int argc, char** argv);

	// ---------------------------------------------------------------- UI API
	// 桌面/Deck 走命令行（上面那个），Android 没有命令行 ⇒ Kotlin 的授权页直调下面这几个。

	enum class probe_status : int
	{
		ok = 0,                 // 普通盘，或已加密但本机授权有效 → 直接放行
		need_license = 1,       // 已加密，本机还没有可用授权（E5/无 license）
		platform_mismatch = 2,  // 这盘是别档的（E8：Windows/Deck/Android 拿错了）
		license_invalid = 3,    // 授权与盘不符，或不是发给本机的（E4/E6）
		unreadable = 4,         // 打不开镜像 / 读不到标签
	};

	struct probe_result
	{
		probe_status status = probe_status::unreadable;
		std::string detail;     // 给 UI 显示的中文说明
		std::string platform;   // 盘上的平台档名称（none/Windows/Steam Deck/Android）
	};

	probe_result probe_image(const std::string& path);

	// 生成申请码（首次会创建设备密钥），并写 config/dimg/request.bin；返回 Base32 文本。失败返回空 + err。
	std::string activation_code(std::string& err);

	// 导入 license.bin 内容：验签 → 试开（确认是发给本机的）→ 才落盘。成功返回 true。
	bool import_license(const std::vector<u8>& blob, std::string& err);
	bool import_license_text(const std::string& base32_text, std::string& err);
	bool import_license_file(const std::string& path, std::string& err);

	// "ACTIVE" / "INACTIVE"，reason 给一句中文说明
	std::string license_summary(std::string& reason);

	// 删设备密钥 + 已导入的 license（重装前/换机后使用）
	bool reset_device_key(std::string& note);
}
