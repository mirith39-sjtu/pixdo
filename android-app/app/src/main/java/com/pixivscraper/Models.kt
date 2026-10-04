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
)

/** 搜索结果里的作品条目 */
data class WorkBrief(
    val id: String,
    val title: String,
    val userName: String,
    val pageCount: Int,
    val xRestrict: Int,
)

/** 作品详情 */
data class WorkDetail(
    val id: String,
    val title: String,
    val author: String,
    val authorId: String,
    val likeCount: Long,
    val viewCount: Long,
    val bookmarkCount: Long,
    val pageCount: Int,
    val isR18: Boolean,
    val tags: List<String>,
    val imageUrls: List<String>,
    val url: String,
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
