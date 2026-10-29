package com.armsx2.ui.license

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.dimg.Dimg
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.ConfirmOverlay
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.settings.ControllerAutoScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 授权（定制版）——整盘加密镜像的离线激活。
 *
 * 流程与桌面版一一对应，只是没有命令行：
 *   ① 点「生成申请码」→ 拿到一串激活码（同时写 config/dimg/request.bin）
 *   ② 把它发给作者（贴聊天窗口也行，就是那串 Base32）
 *   ③ 作者回 license.bin / 或者一串授权码 → 这里「导入」
 *
 * 没有授权时打不开加密的盘：启动闸门会拦下并跳到这里（见 MainActivityRuntime.launchGame）。
 */
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // 一次就好：JNI 会读 license.bin，别每次重组都走一遍
    val supported = remember { Dimg.supported() }
    var status by remember { mutableStateOf<Dimg.Status?>(null) }
    var code by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            status = withContext(Dispatchers.IO) { Dimg.status() }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { Dimg.importFile(uri.toString()) }
            message = if (result.isEmpty()) "授权已导入，可以开始游戏了" else result
            busy = false
            refresh()
        }
    }

    val scroll = rememberScrollState()
    ControllerAutoScroll(scroll)

    ArmsBackdrop {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            ArmsTopBar(
                title = "授权（Android 定制版）",
                leading = { RoundAction("←", "返回", onBack) },
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val st = status
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column {
                        Text("激活状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when {
                                st == null -> "读取中…"
                                st.active -> "已激活"
                                else -> "未激活"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        st?.let {
                            Text(it.reason, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!supported) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "这个安装包不带授权功能（不是定制版）：加密的盘打不开。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                GlassPanel(Modifier.fillMaxWidth()) {
                    Column {
                        Text("第一步：生成申请码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点下面的按钮，把这串码发给我（QQ / 微信都行）。" +
                                "同时会写一份 config/dimg/request.bin，两个任选其一给我。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val (generated, err) = withContext(Dispatchers.IO) { Dimg.requestCode() }
                                        busy = false
                                        if (generated.isEmpty()) {
                                            message = if (err.isEmpty()) "生成失败" else err
                                        } else {
                                            code = generated
                                            message = "申请码已生成：复制后发给我"
                                        }
                                    }
                                },
                            ) { Text(if (busy) "请稍等…" else "生成申请码") }

                            Button(
                                enabled = code.isNotEmpty(),
                                onClick = {
                                    clipboard.setText(AnnotatedString(code))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                },
                            ) { Text("复制") }
                        }

                        if (code.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    code,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                GlassPanel(Modifier.fillMaxWidth()) {
                    Column {
                        Text("第二步：导入授权", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "拿到 license.bin 就点「选择文件」；只拿到一串激活码就粘到下面。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pasted,
                            onValueChange = { pasted = it },
                            label = { Text("激活码 / 授权文本") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy && pasted.isNotBlank(),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { Dimg.importText(pasted) }
                                        busy = false
                                        message = if (result.isEmpty()) "授权已导入，可以开始游戏了" else result
                                        if (result.isEmpty()) pasted = ""
                                        refresh()
                                    }
                                },
                            ) { Text("导入激活码") }

                            Button(
                                enabled = !busy,
                                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                            ) { Text("选择 license.bin") }
                        }

                        if (message.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                GlassPanel(Modifier.fillMaxWidth()) {
                    Column {
                        Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "设备密钥存在 AndroidKeyStore（StrongBox / TEE）里，导不出来也拷不走；" +
                                "所以卸载重装、换手机、换安装包签名之后，旧授权都会失效 —— 重新生成申请码就行。\n" +
                                "另外：Android 版认的是「Android 档」的盘，Windows / Steam Deck 的 ISO 打不开，反之亦然。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = !busy,
                            onClick = { confirmReset = true },
                        ) { Text("重置设备密钥") }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (confirmReset) {
        ConfirmOverlay(
            title = "重置设备密钥？",
            message = "会删掉 AndroidKeyStore 里的密钥和已导入的授权。已发出的 license 随之失效，" +
                "之后必须重新生成申请码并重新签发。",
            destructive = true,
            confirmLabel = "重置",
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                busy = true
                scope.launch {
                    val note = withContext(Dispatchers.IO) { Dimg.reset() }
                    busy = false
                    message = note
                    code = ""
                    refresh()
                }
            },
            idPrefix = "dimg-reset",
        )
    }
}
