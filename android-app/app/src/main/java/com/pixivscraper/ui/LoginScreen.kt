package com.pixivscraper.ui

import android.annotation.SuppressLint
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pixivscraper.PixivApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用内登录页：WebView 打开 pixiv，登录状态由真实接口轮询检测
 * （游客也有 PHPSESSID，不能只看 cookie 是否存在）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var checking by remember { mutableStateOf(false) }

    // 自动轮询：登录完成后自动返回
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            if (PixivApi.isLoggedIn()) {
                onLoggedIn()
                break
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = "登录 pixiv（需要 VPN / 代理）",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = WebViewClient()
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl("https://www.pixiv.net/")
                    webView = this
                }
            },
            modifier = Modifier.weight(1f),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "登录完成后会自动返回",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        val ok = PixivApi.isLoggedIn()
                        checking = false
                        if (ok) {
                            onLoggedIn()
                        } else {
                            Toast.makeText(context, "还未检测到登录，请先完成登录", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) {
                Text("已完成登录")
            }
        }
    }
}
