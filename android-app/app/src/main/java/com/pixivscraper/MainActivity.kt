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
import com.pixivscraper.ui.HelpScreen
import com.pixivscraper.ui.LoginScreen
import com.pixivscraper.ui.MainScreen
import com.pixivscraper.ui.PixivScraperTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PixivScraperTheme {
                val vm: MainViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf("main") }

                BackHandler(enabled = screen != "main") { screen = "main" }

                when (screen) {
                    "login" -> LoginScreen(
                        onBack = { screen = "main" },
                        onLoggedIn = {
                            vm.refreshLogin()
                            screen = "main"
                            Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
                        },
                    )
                    "help" -> HelpScreen(onBack = { screen = "main" })
                    else -> MainScreen(
                        vm = vm,
                        onOpenLogin = { screen = "login" },
                        onOpenHelp = { screen = "help" },
                    )
                }
            }
        }
    }
}
