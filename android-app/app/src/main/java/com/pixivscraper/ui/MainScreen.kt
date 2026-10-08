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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.pixivscraper.MainViewModel
import com.pixivscraper.NicheFetishes
import com.pixivscraper.RunState
import com.pixivscraper.ScraperConfig
import com.pixivscraper.TagSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// 每次 App 启动最多自动弹一次通知权限请求（防止在页面间切换时重复弹）
private object NotifPermAutoGate {
    @Volatile var asked = false
}

@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenLogin: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val context = LocalContext.current
    val logs by vm.logs.collectAsState()
    val config = vm.config
    val listState = rememberLazyListState()

    var showClearDialog by remember { mutableStateOf(false) }
    var tagText by remember { mutableStateOf(config.tag) }
    var maxText by remember { mutableStateOf(config.maxImages.toString()) }
    var minText by remember { mutableStateOf(config.minLikes.toString()) }

    // Android 13+ 通知权限：用于前台服务的进度通知（拒绝也能运行，只是看不到通知）
    val notifyPermDeniedToast: () -> Unit = {
        Toast.makeText(
            context,
            "通知权限未开启：任务会继续运行，但看不到进度通知\n可在 系统设置 → 应用 → pixdo → 通知 中开启",
            Toast.LENGTH_LONG,
        ).show()
    }

    // 点「开始爬取」时申请权限：无论允许与否都会开始任务
    val notifPermLauncherRun = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) notifyPermDeniedToast()
        vm.start()
    }

    // 打开「运行通知」开关时申请权限：只申请，不启动任务
    val notifPermLauncherSwitch = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) notifyPermDeniedToast()
    }

    val notifPermNeeded: () -> Boolean = {
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
    }

    // 一进入应用就自动申请通知权限（仅 Android 13+ 且尚未授权时）
    val notifPermLauncherAuto = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 静默处理：拒绝后的引导由「运行通知」开关 / 开始爬取处的提示负责，
        // 避免每次打开 App 都弹 Toast 打扰用户
    }

    LaunchedEffect(Unit) {
        if (!NotifPermAutoGate.asked && notifPermNeeded()) {
            NotifPermAutoGate.asked = true
            notifPermLauncherAuto.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val promptNotifPerm: () -> Unit = {
        if (notifPermNeeded()) {
            notifPermLauncherSwitch.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val beginRun: () -> Unit = {
        if (vm.config.notifyRun && notifPermNeeded()) {
            notifPermLauncherRun.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.start()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
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
        } else if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            beginRun()
        }
    }

    // 自动跟随日志：仅当用户本来就贴在底部时才滚动；往上翻阅历史时不再强制拉回
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && isAtBottom && !listState.isScrollInProgress) {
            val target = (logs.size + 3).coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
            if (target >= 0) listState.animateScrollToItem(target)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "pixdo", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenHelp) { Text("说明") }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 0: 登录状态
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val (txt, color) = when (vm.loginState) {
                            1 -> "登录状态：已登录 ✓" to MaterialTheme.colorScheme.primary
                            2 -> "登录状态：未登录" to MaterialTheme.colorScheme.error
                            else -> "登录状态：检测中…" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = txt,
                            color = color,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onOpenLogin) { Text("登录") }
                        TextButton(onClick = { vm.refreshLogin() }) { Text("检测") }
                    }
                }
            }

            // 1: 配置
            item {
                ConfigCard(
                    config = config,
                    onChange = { transform -> vm.updateConfig(transform) },
                    tagText = tagText,
                    onTagText = { t ->
                        tagText = t
                        vm.updateConfig { c -> c.copy(tag = t) }
                    },
                    fetchSuggestions = { kw -> vm.fetchTagSuggestions(kw) },
                    onEnableNotify = promptNotifPerm,
                    maxText = maxText,
                    onMaxText = { raw ->
                        val t = raw.filter { it.isDigit() }.take(5)
                        maxText = t
                        t.toIntOrNull()?.let { v ->
                            vm.updateConfig { c -> c.copy(maxImages = v.coerceAtLeast(1)) }
                        }
                    },
                    minText = minText,
                    onMinText = { raw ->
                        val t = raw.filter { it.isDigit() }.take(6)
                        minText = t
                        t.toIntOrNull()?.let { v ->
                            vm.updateConfig { c -> c.copy(minLikes = v) }
                        }
                    },
                    onClearHistory = { showClearDialog = true },
                )
            }

            // 2: 按钮与状态
            item {
                Column {
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = vm.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 3: 日志标题
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "运行日志", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.clearLogs() }) { Text("清空日志") }
                }
            }

            // 4+: 日志内容
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

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空查重记录") },
            text = { Text("清空后所有作品都会被当作新作品重新处理。确定要清空吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    val msg = vm.clearHistory()
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    config: ScraperConfig,
    onChange: ((ScraperConfig) -> ScraperConfig) -> Unit,
    tagText: String,
    onTagText: (String) -> Unit,
    fetchSuggestions: suspend (String) -> List<TagSuggestion>,
    onEnableNotify: () -> Unit,
    maxText: String,
    onMaxText: (String) -> Unit,
    minText: String,
    onMinText: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    // ---- 标签实时联想（pixiv 官方接口；选完候选后不再自动弹出，点击输入框才恢复）----
    var suggestEnabled by remember { mutableStateOf(false) }
    var showNicheDialog by remember { mutableStateOf(false) }
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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
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
                    // 关键：不抢焦点。默认的弹出菜单会夺走输入框焦点，
                    // 导致每输入一个字输入法就被收起（无法连续打字）
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

            Spacer(Modifier.height(8.dp))
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
                        onClick = { onChange { c -> c.copy(order = value) } },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = maxText,
                    onValueChange = onMaxText,
                    label = { Text("下载数量") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = minText,
                    onValueChange = onMinText,
                    label = { Text("最低点赞") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(4.dp))
            SwitchRow("过滤 AI 生成", config.filterAi) { v ->
                onChange { c -> c.copy(filterAi = v) }
            }
            SwitchRow("包含 R18 作品", config.includeR18) { v ->
                onChange { c -> c.copy(includeR18 = v) }
            }
            SwitchRow("仅 R18 模式", config.r18Only) { v ->
                onChange { c -> c.copy(r18Only = v) }
            }
            SwitchRow("查重（跳过已处理 ID）", config.dedup) { v ->
                onChange { c -> c.copy(dedup = v) }
            }
            SwitchRow("跳过已过滤作品（低赞/AI）", config.dedupSkipFiltered) { v ->
                onChange { c -> c.copy(dedupSkipFiltered = v) }
            }
            SwitchRow("过滤小众性癖（R18）", config.filterNicheR18) { v ->
                onChange { c -> c.copy(filterNicheR18 = v) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (config.allowedNiche.isEmpty()) {
                        "允许的性癖：全部过滤"
                    } else {
                        "允许的性癖（${config.allowedNiche.size}）：" +
                            config.allowedNiche.joinToString("、") { NicheFetishes.labelOf(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showNicheDialog = true }) { Text("选择…") }
            }
            SwitchRow("删除偏好学习（beta）", config.learnPrefer) { v ->
                onChange { c -> c.copy(learnPrefer = v) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "偏好削减强度 ${config.preferStrength}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(150.dp),
                )
                Slider(
                    value = config.preferStrength.toFloat(),
                    onValueChange = { v -> onChange { c -> c.copy(preferStrength = v.toInt()) } },
                    valueRange = 0f..100f,
                    steps = 9,
                    modifier = Modifier.weight(1f),
                )
            }
            SwitchRow("运行通知（后台 / 锁屏下载）", config.notifyRun) { v ->
                onChange { c -> c.copy(notifyRun = v) }
                if (v) onEnableNotify()
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClearHistory) { Text("清空查重记录") }
                Text(
                    text = "全删自动补下；只删一部分视为有意保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 小众性癖（R18）：勾选允许的类别，其余过滤
    if (showNicheDialog) {
        val selected = remember { mutableStateListOf<String>().apply { addAll(config.allowedNiche) } }
        AlertDialog(
            onDismissRequest = { showNicheDialog = false },
            title = { Text("允许下载的性癖（R18）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "勾选 = 允许；未勾选的类别会被过滤（全部不勾 = 过滤所有小众性癖）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { selected.clear() }) { Text("全不选") }
                        TextButton(onClick = {
                            selected.clear()
                            NicheFetishes.CATEGORIES.forEach { selected.add(it.first) }
                        }) { Text("全选") }
                    }
                    NicheFetishes.CATEGORIES.forEach { (key, label, tags) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = key in selected,
                                onCheckedChange = { v ->
                                    if (v) {
                                        if (key !in selected) selected.add(key)
                                    } else {
                                        selected.remove(key)
                                    }
                                },
                            )
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    tags.take(4).joinToString("、"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange { c -> c.copy(allowedNiche = selected.toList()) }
                    showNicheDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNicheDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
