package com.pixivscraper

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 运行进度快照（主页面显示用） */
data class RunProgress(
    val phase: String = "",
    val downloading: Boolean = false,
    val downloaded: Int = 0,
    val target: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var config by mutableStateOf(loadConfig(app))
        private set

    var running by mutableStateOf(false)
        private set

    var statusText by mutableStateOf("就绪")
        private set

    /** 0=未检测/检测中 1=已登录 2=未登录 */
    var loginState by mutableStateOf(0)
        private set

    val logs = MutableStateFlow<List<String>>(emptyList())

    /** 低产提醒：待用户选择的询问（继续查找 / 放宽点赞条件），来自 RunState */
    var pendingAsk by mutableStateOf<LowYieldAsk?>(null)
        private set

    /** 运行进度（阶段 / 下载进度），由 RunState 轮询同步给界面 */
    var progress by mutableStateOf(RunProgress())
        private set

    /** 最近一次运行结果：null = 还没跑过，true = 完成，false = 中断 */
    var lastRunOk by mutableStateOf<Boolean?>(null)
        private set

    private var job: Job? = null

    @Volatile
    private var stopFlag = false

    init {
        refreshLogin()
        // 低产提醒：轮询 RunState 中的询问（引擎等待回答时会暂停继续扫描）
        viewModelScope.launch {
            while (true) {
                val a = RunState.pendingAsk
                if (pendingAsk?.id != a?.id) pendingAsk = a
                val p = RunProgress(
                    phase = RunState.phase,
                    downloading = RunState.downloading,
                    downloaded = RunState.downloaded,
                    target = RunState.target,
                )
                if (progress != p) progress = p
                delay(400)
            }
        }
    }

    fun updateConfig(transform: (ScraperConfig) -> ScraperConfig) {
        config = transform(config)
        saveConfig(getApplication(), config)
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    fun refreshLogin() {
        viewModelScope.launch {
            loginState = 0
            val ok = PixivApi.isLoggedIn()
            loginState = if (ok) 1 else 2
        }
    }

    fun start() {
        if (running) return
        running = true
        stopFlag = false
        lastRunOk = null
        logs.value = emptyList()
        statusText = "运行中…"
        val cfg = config

        // 前台服务 + 常驻通知：后台/锁屏时防止系统回收进程，并展示运行进度
        RunState.begin(cfg.maxImages)
        if (cfg.notifyRun) {
            if (!NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()) {
                appendLog("[!] 通知未开启：任务会继续，但通知栏看不到进度（设置 → 应用 → pixdo → 通知）")
            }
            ScrapeService.start(getApplication())
        } else {
            appendLog("[*] 已关闭「运行通知」：切后台可能被系统中断，建议保持 App 在前台")
        }

        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = try {
                    ScraperEngine(getApplication()).run(
                        cfg,
                        ::appendLog,
                        isStopped = { stopFlag || RunState.stopFlag },
                        onDownloadStart = { RunState.enterDownload() },
                        onProgress = { done -> RunState.progress(done) },
                        onPhase = { text -> RunState.updatePhase(text) },
                    )
                } catch (e: Exception) {
                    RunResult(false, reason = e.message ?: "未知错误")
                }
                // 结束快照：前台服务据此弹出「完成通知」
                RunState.endDownloaded = result.downloaded
                RunState.endSkipped = result.skippedDup
                RunState.endReason = if (result.ok) null
                    else result.reason.ifBlank { "任务未完成，请查看应用内日志" }
                running = false
                lastRunOk = result.ok
                statusText = if (result.ok) {
                    buildString {
                        append("完成 — 下载 ${result.downloaded} 个作品")
                        if (result.skippedDup > 0) append("，查重跳过 ${result.skippedDup} 个")
                    }
                } else {
                    "终止 — ${result.reason}"
                }
                refreshLogin()
            } finally {
                // 无论正常结束还是被取消，都收起通知
                RunState.finish()
            }
        }
    }

    fun stop() {
        stopFlag = true
        RunState.stopFlag = true
        appendLog("[!] 已请求停止（等待当前步骤完成）")
    }

    /** 回答低产提醒（应用内弹窗用） */
    fun answerAsk(id: Int, choice: Int) = RunState.answerAsk(id, choice)

    fun clearHistory(): String = HistoryDb.clear(getApplication())

    private var tagCache: TagSuggestCache? = null

    /**
     * 标签联想：本地缓存优先 → 在线多层查询 → 离线池兜底。
     * 在线查询做「原文 + 简繁变体 + 逐级去尾」：中文名的尾部常是假名
     * （如 百合园圣娅 → 百合園セイア），而 pixiv 联想接口只做前缀匹配，必须靠去尾命中。
     */
    suspend fun fetchTagSuggestions(keyword: String): List<TagSuggestion> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext emptyList()
            val cache = tagCache ?: TagSuggestCache(getApplication()).also { tagCache = it }
            val cached = cache.getQuery(kw)
            if (!cached.isNullOrEmpty()) return@withContext cached

            val merged = LinkedHashMap<String, TagSuggestion>()
            for (level in TagSuggester.queryLevels(kw)) {
                val results = coroutineScope {
                    level.map { v -> async { PixivApi.suggestTags(v) } }.awaitAll()
                }
                results.forEach { list -> list.forEach { mergeSuggestion(merged, it) } }
                if (merged.size >= 6) break
            }

            if (merged.isNotEmpty()) {
                val ranked = merged.values
                    .sortedWith(
                        compareByDescending<TagSuggestion> { TagSuggester.score(kw, it) }
                            .thenByDescending { it.accessCount }
                    )
                    .take(10)
                cache.saveQuery(kw, ranked)
                ranked
            } else {
                cache.searchPool(kw, 10)
            }
        }

    /** 合并同一个 tag：优先保留带中文翻译的条目，其次保留热度更高的 */
    private fun mergeSuggestion(merged: LinkedHashMap<String, TagSuggestion>, s: TagSuggestion) {
        val old = merged[s.tagName]
        if (old == null) {
            merged[s.tagName] = s
            return
        }
        val preferNew = when {
            old.translation.isEmpty() && s.translation.isNotEmpty() -> true
            old.translation.isNotEmpty() && s.translation.isEmpty() -> false
            else -> s.accessCount > old.accessCount
        }
        if (preferNew) merged[s.tagName] = s
    }

    private fun appendLog(msg: String) {
        logs.update { old ->
            val nl = old + msg
            if (nl.size > 2000) nl.takeLast(1500) else nl
        }
    }

    private companion object {
        const val PREF = "config"

        fun loadConfig(context: Context): ScraperConfig {
            val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val d = ScraperConfig()
            return ScraperConfig(
                tag = sp.getString("tag", d.tag) ?: d.tag,
                order = sp.getString("order", d.order) ?: d.order,
                maxImages = sp.getInt("maxImages", d.maxImages),
                minLikes = sp.getInt("minLikes", d.minLikes),
                filterAi = sp.getBoolean("filterAi", d.filterAi),
                includeR18 = sp.getBoolean("includeR18", d.includeR18),
                r18Only = sp.getBoolean("r18Only", d.r18Only),
                dedup = sp.getBoolean("dedup", d.dedup),
                dedupSkipFiltered = sp.getBoolean("dedupSkipFiltered", d.dedupSkipFiltered),
                redownloadDeleted = sp.getBoolean("redownloadDeleted", d.redownloadDeleted),
                filterNicheR18 = sp.getBoolean("filterNicheR18", d.filterNicheR18),
                allowedNiche = (sp.getString("allowedNiche", "") ?: "")
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() },
                learnPrefer = sp.getBoolean("learnPrefer", d.learnPrefer),
                preferStrength = sp.getInt("preferStrength", d.preferStrength),
                notifyRun = sp.getBoolean("notifyRun", d.notifyRun),
            )
        }

        fun saveConfig(context: Context, c: ScraperConfig) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("tag", c.tag)
                .putString("order", c.order)
                .putInt("maxImages", c.maxImages)
                .putInt("minLikes", c.minLikes)
                .putBoolean("filterAi", c.filterAi)
                .putBoolean("includeR18", c.includeR18)
                .putBoolean("r18Only", c.r18Only)
                .putBoolean("dedup", c.dedup)
                .putBoolean("dedupSkipFiltered", c.dedupSkipFiltered)
                .putBoolean("redownloadDeleted", c.redownloadDeleted)
                .putBoolean("filterNicheR18", c.filterNicheR18)
                .putString("allowedNiche", c.allowedNiche.joinToString(","))
                .putBoolean("learnPrefer", c.learnPrefer)
                .putInt("preferStrength", c.preferStrength)
                .putBoolean("notifyRun", c.notifyRun)
                .apply()
        }
    }
}
