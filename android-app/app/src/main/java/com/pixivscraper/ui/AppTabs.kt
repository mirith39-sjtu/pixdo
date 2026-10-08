package com.pixivscraper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pixivscraper.MainViewModel

private val TAB_TITLES = listOf("爬取", "操作说明", "设置", "关于")

/** 顶部分页：爬取（主操作）/ 操作说明 / 设置 / 关于 */
@Composable
fun AppTabs(
    vm: MainViewModel,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenLog: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            TAB_TITLES.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { onTabChange(index) },
                    text = { Text(title) },
                )
            }
        }
        when (tab) {
            0 -> ScrapeScreen(
                vm = vm,
                onOpenLog = onOpenLog,
                onGoSettings = { onTabChange(2) },
            )
            1 -> GuideScreen()
            2 -> SettingsScreen(vm = vm, onOpenLogin = onOpenLogin)
            else -> AboutScreen()
        }
    }
}
