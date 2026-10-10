package com.pixivscraper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 关于页：使用风险 + 版本信息 + AI 生成声明 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "未知"
        }.getOrNull() ?: "未知"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ---- 使用风险（醒目） ----
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = "⚠ 使用风险（必读）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "· 本应用通过 pixiv 公开接口批量获取内容。虽然内置了请求间隔与\n" +
                        "   限流自动重试，但短时间内大量下载仍可能被判定为异常访问，\n" +
                        "   可能导致：接口限流、账号功能受限，甚至封号。\n" +
                        "· 建议保持默认间隔、单次下载量不要过大、避免长时间连续运行；\n" +
                        "   介意风险请使用小号登录。\n" +
                        "· 下载内容仅供个人学习与收藏，版权归原作者所有，请勿传播或用于商业用途。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f,
                )
            }
        }

        SectionTitle("版本信息")
        Body(
            "· 应用名称：pixdo\n" +
                "· 版本号：$version\n" +
                "· 渠道：正式版\n" +
                "· 运行环境：Android 8.0 及以上"
        )

        SectionTitle("关于本项目")
        Body(
            "· 本项目（含代码、界面与文案）全部由 AI 生成，仅供个人学习与技术研究使用。\n" +
                "· 本应用与 pixiv 官方无关；请遵守 pixiv 的使用条款与当地法律法规。\n" +
                "· 使用者需自行承担因使用本工具产生的一切风险与责任（包括但不限于账号封禁、数据丢失等）。\n" +
                "· 请尊重创作者版权，支持原作者。"
        )

        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "pixdo · $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
