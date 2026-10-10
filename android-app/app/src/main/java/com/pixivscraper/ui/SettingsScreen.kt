package com.pixivscraper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pixivscraper.MainViewModel
import com.pixivscraper.NicheFetishes

/**
 * 设置页：登录状态检测 + 从主操作页移过来的各项设置。
 * 每个设置项都带一句功能说明（不含具体参数），条件相关的控件按开关状态显示。
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onOpenLogin: () -> Unit) {
    val context = LocalContext.current
    val config = vm.config

    var showNicheDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    // 「运行通知」开启时申请通知权限（Android 13+）
    val notifyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "通知权限未开启：下载会在前台进行，但看不到进度通知\n可在 系统设置 → 应用 → pixdo → 通知 中开启",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val promptNotifyPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- pixiv 账号（登录状态检测在这里） ----
        SettingCard(
            title = "pixiv 账号",
            desc = "登录后可以下载 R18 作品（还需在 pixiv 网页版开启 R-18 显示设置）。" +
                "登录信息只保存在本机，不会上传。",
            control = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.refreshLogin() }) { Text("检测") }
                    TextButton(onClick = onOpenLogin) { Text("登录") }
                }
            },
            extra = {
                val (txt, isError) = when (vm.loginState) {
                    1 -> "当前状态：已登录" to false
                    2 -> "当前状态：未登录" to true
                    else -> "当前状态：检测中…" to false
                }
                Text(
                    text = txt,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        // ---- 过滤 AI ----
        SettingCard(
            title = "过滤 AI 生成",
            desc = "过滤掉被标记为 AI 生成的作品。",
            control = {
                Switch(checked = config.filterAi, onCheckedChange = { v ->
                    vm.updateConfig { c -> c.copy(filterAi = v) }
                })
            },
        )

        // ---- 查重 ----
        SettingCard(
            title = "查重（跳过已处理的 ID）",
            desc = "已经下载过的作品不会重复下载；整组删掉的作品视为不喜欢，不再补下并计入偏好学习" +
                "（一次几乎删光则视为清理，下次重新下载）。",
            control = {
                Switch(checked = config.dedup, onCheckedChange = { v ->
                    vm.updateConfig { c -> c.copy(dedup = v) }
                })
            },
            extra = {
                if (config.dedup) {
                    SwitchRow("跳过已过滤作品（低赞 / AI）", config.dedupSkipFiltered) { v ->
                        vm.updateConfig { c -> c.copy(dedupSkipFiltered = v) }
                    }
                    SwitchRow("整组删除后重新下载（视为清理）", config.redownloadDeleted) { v ->
                        vm.updateConfig { c -> c.copy(redownloadDeleted = v) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showClearDialog = true }) { Text("清空查重记录") }
                    Text(
                        text = "清空后所有作品都会重新处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        // ---- 小众性癖过滤 ----
        SettingCard(
            title = "小众性癖过滤（R18）",
            desc = "过滤 R18 作品中的小众性癖标签；只有勾选的类别会被下载。",
            control = {
                Switch(checked = config.filterNicheR18, onCheckedChange = { v ->
                    vm.updateConfig { c -> c.copy(filterNicheR18 = v) }
                })
            },
            extra = {
                if (config.filterNicheR18) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (config.allowedNiche.isEmpty()) {
                                "允许的性癖：全部过滤"
                            } else {
                                "允许的性癖（${config.allowedNiche.size} 类）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showNicheDialog = true }) { Text("选择…") }
                    }
                }
            },
        )

        // ---- 删除偏好学习：强度只在开关打开时出现 ----
        SettingCard(
            title = "删除偏好学习",
            desc = "根据你的删除行为，自动统计不喜欢的标签并在排序时降低它们的权重（只影响顺序，" +
                "不会直接排除）。默认只看「整组删除」——清理重复图 / 无用图不会影响学习。" +
                "偏好按搜索标签分别学习；与标签高度伴随的基础特征不会被计入。",
            control = {
                Switch(checked = config.learnPrefer, onCheckedChange = { v ->
                    vm.updateConfig { c -> c.copy(learnPrefer = v) }
                })
            },
            extra = {
                if (config.learnPrefer) {
                    SwitchRow("挑片删除也计入偏好学习", config.learnFromPartial) { v ->
                        vm.updateConfig { c -> c.copy(learnFromPartial = v) }
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
                            onValueChange = { v ->
                                vm.updateConfig { c -> c.copy(preferStrength = v.toInt()) }
                            },
                            valueRange = 0f..100f,
                            steps = 9,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )

        // ---- 运行通知 ----
        SettingCard(
            title = "运行通知（后台 / 锁屏下载）",
            desc = "下载时显示常驻进度通知，切后台或锁屏后继续运行；任务结束会弹出完成提醒。",
            control = {
                Switch(checked = config.notifyRun, onCheckedChange = { v ->
                    vm.updateConfig { c -> c.copy(notifyRun = v) }
                    if (v) promptNotifyPermission()
                })
            },
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = "设置会保存在本机，下次启动继续生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    vm.updateConfig { c -> c.copy(allowedNiche = selected.toList()) }
                    showNicheDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNicheDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 设置项卡片：标题 + 右侧控件 + 一句功能说明（可选附加内容） */
@Composable
fun SettingCard(
    title: String,
    desc: String,
    control: @Composable () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                control()
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (extra != null) {
                Spacer(Modifier.height(6.dp))
                extra()
            }
        }
    }
}
