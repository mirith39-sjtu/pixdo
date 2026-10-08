package com.pixivscraper

/** 爬取配置（与桌面版 pixiv_scraper.py 的 CONFIG 对应） */
data class ScraperConfig(
    val tag: String = "天童ケイ",
    val order: String = "popular_d",
    val maxImages: Int = 60,
    val minLikes: Int = 50,
    val filterAi: Boolean = true,
    val includeR18: Boolean = true,
    val r18Only: Boolean = false,
    val dedup: Boolean = true,
    val dedupSkipFiltered: Boolean = true,
    val filterNicheR18: Boolean = true,
    val allowedNiche: List<String> = emptyList(),
    val learnPrefer: Boolean = true,
    val preferStrength: Int = 50,
    val notifyRun: Boolean = true,
)

/** 搜索结果里的作品条目（illustType: 0=插画 1=漫画 2=动图） */
data class WorkBrief(
    val id: String,
    val title: String,
    val xRestrict: Int,
    val illustType: Int = 0,
)

/** 作品详情 */
data class WorkDetail(
    val id: String,
    val title: String,
    val author: String,
    val authorId: String,
    val likeCount: Long,
    val pageCount: Int,
    val isR18: Boolean,
    val tags: List<String>,
    val imageUrls: List<String>,
    val url: String,
    val illustType: Int = 0,
) {
    /** 动图（ugoira）：需要走 ugoira_meta 下载帧序列 zip 合成 GIF */
    val isUgoira: Boolean get() = illustType == 2
}

/** 动图元信息：zip 地址 + 逐帧延迟 */
data class UgoiraMeta(
    val zipUrl: String,
    val frames: List<UgoiraFrame>,
)

/** 动图的一帧：文件名 + 延迟（毫秒） */
data class UgoiraFrame(
    val file: String,
    val delayMs: Int,
)

/** 一次运行的结果 */
data class RunResult(
    val ok: Boolean,
    val downloaded: Int = 0,
    val skippedDup: Int = 0,
    val reason: String = "",
)

/** 标签联想候选 */
data class TagSuggestion(
    val tagName: String,
    val translation: String = "",
    val accessCount: Long = 0L,
    val type: String = "",
)
