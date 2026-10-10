package com.pixivscraper

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixivscraper.ui.AppTabs
import com.pixivscraper.ui.LogScreen
import com.pixivscraper.ui.LoginScreen
import com.pixivscraper.ui.PixivScraperTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PixivScraperTheme {
                val vm: MainViewModel = viewModel()
                // tab: 0=爬取 1=操作说明 2=设置 3=关于
                var tab by rememberSaveable { mutableStateOf(0) }
                // screen: tabs=顶部分页，login / log 为独立页面
                var screen by rememberSaveable { mutableStateOf("tabs") }

                BackHandler(enabled = screen != "tabs") { screen = "tabs" }

                when (screen) {
                    "login" -> LoginScreen(
                        onBack = { screen = "tabs" },
                        onLoggedIn = {
                            vm.refreshLogin()
                            screen = "tabs"
                            Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
                        },
                    )
                    "log" -> LogScreen(vm = vm, onBack = { screen = "tabs" })
                    else -> AppTabs(
                        vm = vm,
                        tab = tab,
                        onTabChange = { tab = it },
                        onOpenLogin = { screen = "login" },
                        onOpenLog = { screen = "log" },
                    )
                }
            }
        }
    }
}
