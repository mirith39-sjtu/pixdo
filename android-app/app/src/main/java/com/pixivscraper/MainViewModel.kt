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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var job: Job? = null

    @Volatile
    private var stopFlag = false

    init {
        refreshLogin()
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

    fun clearHistory(): String = HistoryDb.clear(getApplication())

    private var tagCache: TagSuggestCache? = null

    /** 标签联想：本地缓存优先 → 在线查询 → 离线池兜底（与 PC 端一致） */
    suspend fun fetchTagSuggestions(keyword: String): List<TagSuggestion> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext emptyList()
            val cache = tagCache ?: TagSuggestCache(getApplication()).also { tagCache = it }
            val cached = cache.getQuery(kw)
            if (!cached.isNullOrEmpty()) return@withContext cached
            val live = PixivApi.suggestTags(kw)
            if (live.isNotEmpty()) {
                cache.saveQuery(kw, live)
                live
            } else {
                cache.searchPool(kw, 10)
            }
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
                .putBoolean("notifyRun", c.notifyRun)
                .apply()
        }
    }
}
