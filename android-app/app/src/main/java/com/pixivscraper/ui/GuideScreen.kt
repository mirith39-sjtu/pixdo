package com.pixivscraper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 操作说明页：怎么用（不做参数说明，具体设置在「设置」页） */
@Composable
fun GuideScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle("使用步骤")
        Body(
            "1. 开启 VPN / 代理（需能正常访问 pixiv）\n" +
                "2. 在「设置」页点「登录」，完成 pixiv 账号登录（自动检测，成功后返回）\n" +
                "3. 在「爬取」页输入标签（支持中文联想日文 Tag），按需调整排序、数量、最低点赞与 R18 选项\n" +
                "4. 点「开始爬取」；过程中可点「停止」中止，进度见通知栏或「运行日志」\n" +
                "5. 下载完成后到系统相册查看即可"
        )

        SectionTitle("保存位置")
        Body(
            "· 图片保存在相册目录 Pictures/PixivScraper/ 下，按标签分文件夹\n" +
                "· R18 与普通作品分开存放，方便分别浏览"
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
                "· 「包含 R18」同时搜索普通与 R18 两条通道；「仅 R18」只下载 R18 作品"
        )

        SectionTitle("动图（うごイラ）说明")
        Body(
            "· 动图会自动下载帧序列并合成为可播放的 GIF\n" +
                "· 合成一次完成，帧数多时需要一些时间，属正常现象"
        )

        SectionTitle("查重说明")
        Body(
            "· 已下载且文件完整的作品自动跳过\n" +
                "· 删除图片后会区分情况：整个作品全删 → 视为清理，下次重新下载；\n" +
                "   只删了一部分（挑掉几张不好看的）→ 视为有意保留，不再补下\n" +
                "· 在「设置」页可清空查重记录，让所有作品重新参与下载"
        )

        SectionTitle("小众性癖过滤（R18）")
        Body(
            "· 内置常见小众性癖的标签库，默认全部过滤（仅对 R18 作品生效）\n" +
                "· 在「设置」页勾选允许的类别后，这些类别的作品才会下载"
        )

        SectionTitle("删除偏好学习（beta）")
        Body(
            "· 从「只删了一部分」的作品里统计功能性标签（如题材、服装、风格等），\n" +
                "   下次运行自动降低它们的排序权重（不会直接排除）\n" +
                "· 角色名、作品名、系列名等身份标签不参与统计；与该标签高度伴随的\n" +
                "   基础标签（例如角色本身就是贫乳时的「贫乳」）同样不会被计入\n" +
                "· 偏好按搜索标签分别学习：换标签后旧偏好不会串台\n" +
                "· 强度可在「设置」页调整；数据来自本机查重记录，清空记录即重置"
        )

        SectionTitle("筛选效率提醒")
        Body(
            "· 检查了很多作品仍凑不够目标数量时，会提醒一次：\n" +
                "   「继续查找」或「放宽最低点赞」（附建议参考值）\n" +
                "· 通知栏可直接选择，应用内也会弹出对话框；\n" +
                "   较长时间未选择会按「继续查找」自动继续"
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
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.6f,
    )
}
