package com.pixivscraper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.pixivscraper.MainViewModel
import com.pixivscraper.RunState
import com.pixivscraper.TagSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// 每次 App 启动最多自动弹一次通知权限请求（防止在页面间切换时重复弹）
private object NotifPermAutoGate {
    @Volatile var asked = false
}

/**
 * 主操作页：只保留常用项（标签 / 数量 / 点赞 / 排序 / R18 / 开始停止），
 * 其余设置见「设置」页，运行日志见独立页面（顶部按钮进入）。
 */
@Composable
fun ScrapeScreen(
    vm: MainViewModel,
    onOpenLog: () -> Unit,
    onGoSettings: () -> Unit,
) {
    val context = LocalContext.current
    val config = vm.config

    var showLoginNeeded by remember { mutableStateOf(false) }
    var tagText by remember { mutableStateOf(config.tag) }
    var maxText by remember { mutableStateOf(config.maxImages.toString()) }
    var minText by remember { mutableStateOf(config.minLikes.toString()) }

    // ---- Android 13+ 通知权限（前台服务进度通知；拒绝也能跑，只是看不到通知）----
    val notifyPermissionNeeded: () -> Boolean = {
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
    }
    val notifyPermDeniedToast: () -> Unit = {
        Toast.makeText(
            context,
            "通知权限未开启：任务会继续运行，但看不到进度通知\n可在 系统设置 → 应用 → pixdo → 通知 中开启",
            Toast.LENGTH_LONG,
        ).show()
    }
    val notifPermLauncherRun = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) notifyPermDeniedToast()
        vm.start()
    }
    val notifPermLauncherAuto = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 静默处理：拒绝后的引导由「设置 → 运行通知」与开始时的提示负责
    }
    LaunchedEffect(Unit) {
        if (!NotifPermAutoGate.asked && notifyPermissionNeeded()) {
            NotifPermAutoGate.asked = true
            notifPermLauncherAuto.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val beginRun: () -> Unit = {
        if (vm.config.notifyRun && notifyPermissionNeeded()) {
            notifPermLauncherRun.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.start()
        }
    }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginRun()
        } else {
            Toast.makeText(context, "未授予存储权限，无法保存图片", Toast.LENGTH_LONG).show()
        }
    }

    val tryStart: () -> Unit = {
        if (vm.config.tag.isBlank()) {
            Toast.makeText(context, "请先填写搜索标签", Toast.LENGTH_SHORT).show()
        } else if (vm.loginState != 1) {
            // 登录状态检测已移至「设置」页：这里提醒并引导过去
            vm.refreshLogin()
            showLoginNeeded = true
        } else if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            beginRun()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1: 搜索条件
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                TagInputField(
                    tagText = tagText,
                    onTagText = { t ->
                        tagText = t
                        vm.updateConfig { c -> c.copy(tag = t) }
                    },
                    fetchSuggestions = { kw -> vm.fetchTagSuggestions(kw) },
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "排序",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    val orders = listOf(
                        "popular_d" to "综合热门",
                        "date_d" to "最新",
                        "popular_male_d" to "男性向",
                        "popular_female_d" to "女性向",
                    )
                    orders.forEach { (value, label) ->
                        FilterChip(
                            selected = config.order == value,
                            onClick = { vm.updateConfig { c -> c.copy(order = value) } },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { raw ->
                            val t = raw.filter { it.isDigit() }.take(5)
                            maxText = t
                            t.toIntOrNull()?.let { v ->
                                vm.updateConfig { c -> c.copy(maxImages = v.coerceAtLeast(1)) }
                            }
                        },
                        label = { Text("下载数量") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { raw ->
                            val t = raw.filter { it.isDigit() }.take(6)
                            minText = t
                            t.toIntOrNull()?.let { v ->
                                vm.updateConfig { c -> c.copy(minLikes = v) }
                            }
                        },
                        label = { Text("最低点赞") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 2: R18（仅 R18 开关只在「包含 R18」开启时出现）
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                SwitchRow("包含 R18 作品", config.includeR18) { v ->
                    vm.updateConfig { c ->
                        c.copy(includeR18 = v, r18Only = if (v) c.r18Only else false)
                    }
                }
                if (config.includeR18) {
                    SwitchRow("仅 R18（不下载普通作品）", config.r18Only) { v ->
                        vm.updateConfig { c -> c.copy(r18Only = v) }
                    }
                }
            }
        }

        // 3: 开始 / 停止
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = tryStart,
                        enabled = !vm.running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("开始爬取")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { vm.stop() },
                        enabled = vm.running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("停止")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = vm.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 4: 运行日志入口
        OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
            Text("运行日志")
        }
    }

    // 未登录提醒：引导到「设置」页完成登录
    if (showLoginNeeded) {
        val stillChecking = vm.loginState != 2
        AlertDialog(
            onDismissRequest = { showLoginNeeded = false },
            title = { Text("需要先登录") },
            text = {
                Text(
                    if (stillChecking) {
                        "正在检测登录状态。\n\n" +
                            "如果还没有登录，请先到「设置」页完成登录" +
                            "（下载 R18 作品必须登录，登录信息只保存在本机）。"
                    } else {
                        "还没有检测到已登录的 pixiv 账号。\n\n" +
                            "请先在「设置」页完成登录（下载 R18 作品必须登录），" +
                            "登录信息只保存在本机。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLoginNeeded = false
                    onGoSettings()
                }) { Text("前往设置") }
            },
            dismissButton = {
                TextButton(onClick = { showLoginNeeded = false }) { Text("取消") }
            },
        )
    }

    // 低产提醒：扫描了很多作品仍凑不够目标时，询问是否放宽点赞条件
    val ask = vm.pendingAsk
    if (ask != null) {
        val tip = if (ask.suggested > 0) "放宽到 ≥${ask.suggested} 赞" else "取消点赞过滤（设为 0）"
        AlertDialog(
            onDismissRequest = { vm.answerAsk(ask.id, RunState.ASK_CONTINUE) },
            title = { Text("筛选效率偏低") },
            text = {
                Text(
                    "已检查 ${ask.scanned} 个作品的详情，只有 ${ask.found} / ${ask.target} 个" +
                        "满足「≥${ask.minLikes} 赞」。\n\n" +
                        "建议$tip —— 按已扫描的 ${ask.sample} 个作品估算，" +
                        "这一档约有 ${ask.estCount} 个作品符合。\n\n" +
                        "要继续按原条件查找，还是$tip？"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.answerAsk(ask.id, RunState.ASK_LOWER) }) {
                    Text(tip)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerAsk(ask.id, RunState.ASK_CONTINUE) }) {
                    Text("继续查找")
                }
            },
        )
    }
}

/** 运行日志（独立页面，从主操作页进入） */
@Composable
fun LogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val logs by vm.logs.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // 自动跟随日志：仅当用户本来就贴在底部时才滚动
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && isAtBottom && !listState.isScrollInProgress) {
            val target = (logs.size - 1).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(text = "运行日志", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(logs.joinToString("\n")))
            }) { Text("复制") }
            TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
        }

        if (logs.isEmpty()) {
            Text(
                text = "暂无日志。回到「爬取」页开始任务后，这里会显示运行过程。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/** 标签输入框 + 联想下拉（选完后不再自动弹出，点击输入框才恢复） */
@Composable
fun TagInputField(
    tagText: String,
    onTagText: (String) -> Unit,
    fetchSuggestions: suspend (String) -> List<TagSuggestion>,
) {
    var suggestEnabled by remember { mutableStateOf(false) }
    var queryTick by remember { mutableStateOf(0) }
    var suggestions by remember { mutableStateOf<List<TagSuggestion>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(tagText, suggestEnabled, queryTick) {
        if (!suggestEnabled) {
            expanded = false
            return@LaunchedEffect
        }
        val kw = tagText.trim()
        if (kw.isEmpty()) {
            suggestions = emptyList()
            expanded = false
            return@LaunchedEffect
        }
        delay(300)                       // 输入防抖
        val result = try {
            fetchSuggestions(kw)
        } catch (e: CancellationException) {
            throw e                        // 用户继续打字：取消旧请求，不显示过期结果
        } catch (e: Exception) {
            emptyList()
        }
        if (suggestEnabled && tagText.trim() == kw) {
            suggestions = result
            expanded = result.isNotEmpty()
        }
    }

    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = tagText,
            onValueChange = { t ->
                onTagText(t)
                suggestEnabled = true   // 继续打字也恢复联想
            },
            label = { Text("搜索标签") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                suggestEnabled = true   // 点击输入框 → 允许联想
                                queryTick++
                            }
                        }
                    }
                },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // 不抢焦点：默认弹出菜单会夺走输入框焦点，导致输入法收起
            properties = PopupProperties(focusable = false),
        ) {
            suggestions.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (s.translation.isNotEmpty() && s.translation != s.tagName) {
                                "${s.tagName}    （${s.translation}）"
                            } else {
                                s.tagName
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onTagText(s.tagName)     // 选中后填入日文 tag
                        suggestEnabled = false   // 选完收起；再次点击输入框才恢复
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 一行「文字 + 开关」 */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
