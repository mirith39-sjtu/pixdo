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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(text = "使用说明", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))

        // ---- 使用风险提示（醒目） ----
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
                        "· 下载内容仅供个人学习与收藏，版权归原作者所有，请勿传播。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f,
                )
            }
        }

        SectionTitle("使用步骤")
        Body(
            "1. 开启 VPN / 代理（需能正常访问 pixiv）\n" +
                "2. 首页点「登录」→ 完成 pixiv 账号登录（自动检测，成功后自动返回）\n" +
                "3. 输入标签（支持中文联想日文 Tag），按需调整排序 / 数量 / 点赞 / R18\n" +
                "4. 点「开始爬取」；图片保存到相册 Pictures/PixivScraper/<标签>-safe | -r18/\n" +
                "5. 通知栏显示进度、可切后台 / 锁屏继续下载（首页可关闭「运行通知」）；点「停止」可中止"
        )

        SectionTitle("网络要求")
        Body(
            "· 需能访问：www.pixiv.net、accounts.pixiv.net（登录）、i.pximg.net（图片）\n" +
                "· 大陆网络必须开启代理（建议全局代理，或把本应用加入分应用代理）\n" +
                "· 登录页打不开 / 一直转圈：更换节点后重试"
        )

        SectionTitle("R18 说明")
        Body(
            "· 需已登录，且账号已开启「设置 → 閲覧設定 → R-18作品の表示」\n" +
                "· 「包含R18」同时搜索普通与 R18 两条通道；「仅R18」只下载 R18 作品"
        )

        SectionTitle("动图（うごイラ）说明")
        Body(
            "· 动图会自动下载帧序列并合成为可播放的 GIF（长边 600px）\n" +
                "· 合成为一步完成，帧数多时需要一些时间，属正常现象"
        )

        SectionTitle("AI 作品过滤建议")
        Body(
            "· 建议在 pixiv「设置 → 显示设置」关闭「展示 AI 生成作品」，\n" +
                "   再配合本应用内的「过滤 AI 生成」开关，从源头过滤 AI 作品"
        )

        SectionTitle("查重说明")
        Body(
            "· 已下载且文件完整的作品自动跳过；手动删除图片后，下次运行自动补下\n" +
                "· 「跳过已过滤作品」默认开启；「清空查重记录」后所有作品重新参与下载"
        )

        SectionTitle("常见问题")
        Body(
            "· 一直显示「未登录」→ 检查代理是否生效，再重新登录\n" +
                "· 搜索无结果 → 检查标签拼写（推荐日文）与代理\n" +
                "· 提示 429 限流 → 程序会自动等待重试，属正常现象\n" +
                "· 后台筛选 / 下载中断 → 设置 → 应用 → pixdo → 电池 → 设为「无限制」\n" +
                "   （部分系统会限制后台联网；应用已使用前台服务 + 唤醒锁保活）\n" +
                "· 中文联想不出结果 → 用更短的词头试试（如「百合园」）；\n" +
                "   角色名末尾常是假名，只输名字片段需先查过相关标签"
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text = "pixdo v1.2 · 仅供个人学习使用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f,
    )
}
