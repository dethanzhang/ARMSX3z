package com.armsx2.ui.license

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 *   ③ 作者回一份 .lic / 或者一串授权码 → 这里「导入」
 *      （P3 起是多份 store：每份落在 config/dimg/licenses/<licid>.lic，可以同时装多份；
 *        打开某张盘时按盘上的 tag 自动挑对应那份；列表里可单独删除）
 *
 * P3 起 core 用「多 license store」：每份授权单独存进 config/dimg/licenses/<licid>.lic，
 * 打开某张盘时按盘上的 tag 自动挑对应的那份。所以这里多了一个「已安装的授权」列表：
 * 装了几份、哪份本机能解（opens）、哪份只能删掉重发，都在那一屏说清楚。
 * 旧的单槽 license.bin 不再读取（升级后要重新导入 .lic）。
 *
 * 没有授权时打不开加密的盘：启动闸门会拦下并跳到这里（见 MainActivityRuntime.launchGame）。
 */
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // 一次就好：JNI 会读授权 store，别每次重组都走一遍
    val supported = remember { Dimg.supported() }
    var status by remember { mutableStateOf<Dimg.Status?>(null) }
    var licenses by remember { mutableStateOf<List<Dimg.License>>(emptyList()) }
    var code by remember { mutableStateOf("") }
    var pasted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // message：删除的结果，显示在列表底下；hint：申请码 / 导入的结果，显示在第二步底下
    var message by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    // 等二次确认的那份授权；null = 没弹确认框
    var pendingDelete by remember { mutableStateOf<Dimg.License?>(null) }

    // 状态与列表永远一起取：删/导入之后两边要同步，不能一个新一个旧
    suspend fun reload() {
        val (st, list) = withContext(Dispatchers.IO) { Dimg.status() to Dimg.licenses() }
        status = st
        licenses = list
    }

    fun refresh() {
        scope.launch { reload() }
    }

    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val (result, count) = withContext(Dispatchers.IO) {
                val r = Dimg.importFile(uri.toString())
                // 成功才再数一遍；失败时保持原提示，不报个假的份数
                r to if (r.isEmpty()) Dimg.licenses().size else 0
            }
            hint = if (result.isEmpty()) "已导入；当前共 $count 份" else result
            busy = false
            reload()
        }
    }

    val scroll = rememberScrollState()
    ControllerAutoScroll(scroll)

    // 全路径只用于排障：整段放在「关于」里，从不出现在列表行上
    val licensesDir = remember {
        runCatching { Dimg.licensesDirPath() }.getOrDefault("config/dimg/licenses")
    }

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
                        // reason 里已经带了「已安装 N 份授权（本机可用 M 份）」这类汇总，原样显示
                        st?.let {
                            Text(it.reason, style = MaterialTheme.typography.bodySmall)
                            if (!it.active && supported) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "有本机可用的 .lic 就会激活（还没有设备密钥就先点「生成申请码」）。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
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

                // ---- 已安装的授权：store 里有什么、哪份本机能用、删哪份 ----
                GlassPanel(Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            "已安装的授权（${licenses.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (licenses.isEmpty()) {
                            Text(
                                "还没有导入授权。把作者发的授权文件逐个导入（一次一份，文件名不限）。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                licenses.forEach { lic ->
                                    LicenseRow(
                                        lic = lic,
                                        busy = busy,
                                        onDelete = { pendingDelete = lic },
                                    )
                                }
                            }
                        }
                        if (message.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                GlassPanel(Modifier.fillMaxWidth()) {
                    Column {
                        Text("第一步：生成申请码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点下面的按钮，把这串码发给作者（QQ / 微信都行）。" +
                                "同时会写一份 config/dimg/request.bin，两个任选其一发给作者。",
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
                                            hint = if (err.isEmpty()) "生成失败" else err
                                        } else {
                                            code = generated
                                            hint = "申请码已生成：复制后发给作者"
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
                            "拿到授权文件就点「选择授权文件」导入（一次一份；文件名不限，" +
                                "作者发的 `.lic`、`license.bin`、`xxx.bin` 都行，只认内容）；" +
                                "只拿到一串激活码就粘到下面。",
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
                                        val (result, count) = withContext(Dispatchers.IO) {
                                            val r = Dimg.importText(pasted)
                                            r to if (r.isEmpty()) Dimg.licenses().size else 0
                                        }
                                        busy = false
                                        hint = if (result.isEmpty()) "已导入；当前共 $count 份" else result
                                        if (result.isEmpty()) pasted = ""
                                        reload()
                                    }
                                },
                            ) { Text("导入激活码") }

                            Button(
                                enabled = !busy,
                                onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                            ) { Text("选择授权文件") }
                        }

                        if (hint.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(hint, style = MaterialTheme.typography.bodySmall)
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
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "可以同时装多份授权（不同平台档、不同批次的盘各一份）。打开某张盘时按盘上的" +
                                "标签自动挑对应的那份，不用手动切换；列表里每份都能单独删除。\n" +
                                "从别人那里拿到的 Windows / Steam Deck 档授权也能导入，但本机打不开，" +
                                "会显示「打不开（E6）」，删掉就行。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "旧版单份 `license.bin` 不再读取：升级到这一版后，把之前那份重新导入一次即可" +
                                "（文件名不限，见上面的目录）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            licensesDir,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }

        // 删除要二次确认：删掉之后对应的盘就打不开了（当然可以找作者重发 / 重新导入）。
        // 用 ConfirmOverlay 而不是 Compose 的 dialog：dialog 是独立窗口，会抢走手柄的
        // KeyEvent，D-pad 到不了确认按钮。它必须画在 ArmsBackdrop 这层 BoxScope 里才盖得住整屏。
        val toDelete = pendingDelete
        if (toDelete != null) {
            ConfirmOverlay(
                title = "删除这份授权？",
                message = "删除后对应的游戏盘会打不开（可重新导入）。\nlicid = ${toDelete.licid}",
                onConfirm = {
                    busy = true
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { Dimg.deleteLicense(toDelete.licid) }
                        busy = false
                        message = if (err.isEmpty()) "已删除：${toDelete.licid}" else err
                        pendingDelete = null
                        reload()
                    }
                },
                onDismiss = { if (!busy) pendingDelete = null },
                confirmLabel = "删除",
                dismissLabel = "取消",
                destructive = true,
                idPrefix = "license-delete",
                confirmEnabled = !busy,
            )
        }
    }
}

/**
 * 一份授权一行：平台 + 收件人（前 8 位就够认人）、licid（排障/对单用）、能不能在本机解开。
 * 路径（file）刻意不显示：又长又没用，真要排障去「关于」里看目录。
 */
@Composable
private fun LicenseRow(
    lic: Dimg.License,
    busy: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${lic.platform} · 收件人 ${shortRecipient(lic.recipient)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "licid = ${lic.licid}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(enabled = !busy, onClick = onDelete) { Text("删除") }
            }
            Text(
                if (lic.opens) "本机可用" else "打不开（E6）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (lic.opens) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!lic.opens && lic.error.isNotEmpty()) {
                Text(
                    lic.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 收件人 32 hex 太长，列表里取前 8 位（后面加省略号）；短的（脏数据）就原样给。 */
private fun shortRecipient(recipient: String): String =
    if (recipient.length > 8) recipient.take(8) + "…" else recipient
