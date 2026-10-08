package com.pixivscraper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * 爬取主流程（与桌面版 pixiv_scraper.py 对齐）：
 * 搜索候选 → 详情过滤 → 排序 → 下载（含查重跳过 / 缺失补下 / 沿用旧文件名）
 */
class ScraperEngine(private val context: Context) {

    companion object {
        /** 低产提醒：基础阈值（详情检查条数）；实际阈值 = max(该值, 目标数×10) */
        private const val LOW_YIELD_BASE = 500

        /** 偏好学习：标签至少被下载过 N 次才参与统计 */
        private const val PREFER_MIN_SEEN = 3

        /** 询问等待上限；超时按「继续查找」处理 */
        private const val ASK_TIMEOUT_MS = 120_000L
    }

    private data class DownloadMeta(
        val id: String,
        val title: String,
        val author: String,
        val likes: Long,
        val ok: Int,
        val total: Int,
    )

    private val apiDelayMs = 800L
    private val downloadDelayMs = 800L

    suspend fun run(
        config: ScraperConfig,
        log: (String) -> Unit,
        isStopped: () -> Boolean,
        onDownloadStart: (() -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null,
        onPhase: ((String) -> Unit)? = null,
    ): RunResult {
        log("=".repeat(56))
        log("  pixdo · Android")
        log("  标签: ${config.tag} | 排序: ${config.order} | 目标: ${config.maxImages} 张")
        val r18Mode = when {
            config.r18Only -> "仅R18"
            !config.includeR18 -> "不含R18"
            else -> "含R18"
        }
        log("  最低点赞: ${config.minLikes} | AI过滤: ${config.filterAi} | R18模式: $r18Mode | 查重: ${if (config.dedup) "开" else "关"}")
        log("=".repeat(56))

        // ---- 登录检查 ----
        if (PixivApi.isLoggedIn()) {
            log("[✓] 登录状态: 已登录")
        } else {
            log("[!] 当前未登录（游客状态）！R18 作品不会出现在搜索结果中。")
            log("    请先在「登录」页完成登录，并确认 VPN / 代理可用；")
            log("    账号需开启 pixiv「设置 → 閲覧設定 → R-18作品の表示」。")
        }

        val store = ImageStore(context)
        var db: HistoryDb? = null
        var records = HashMap<String, HistoryDb.WorkRecord>()
        var index = HashSet<String>()

        try {
            // ---- 查重库：加载 + 与磁盘对账（手动删除的图片在这里被检测到） ----
            if (config.dedup) {
                try {
                    val d = HistoryDb(context)
                    db = d
                    records = HashMap(d.loadAll())
                    index = HashSet(store.buildIndex())
                    val downloaded = records.values.count { it.status == "downloaded" }
                    val missing = records.values.count { it.status == "missing" }
                    val pruned = records.values.count { it.status == "pruned" }
                    val filtered = records.values.count { it.status == "filtered" }
                    log("[*] 查重库: 共 ${records.size} 条（已下载 $downloaded / 文件缺失 $missing / 已精选 $pruned / 已过滤 $filtered）")
                    val rc = reconcile(records, index, d)
                    if (rc.toMissing > 0) log("[*] 查重: ${rc.toMissing} 条记录被整体删除 → 下次将重新下载")
                    if (rc.toPruned > 0) log("[*] 查重: ${rc.toPruned} 条记录只删了一部分 → 视为有意保留，不再补下")
                    if (rc.toDone > 0) log("[*] 查重: ${rc.toDone} 条记录的文件已恢复（状态更新为已下载）")
                } catch (e: Exception) {
                    log("[!] 查重库打开失败（本次不做去重）: ${e.message}")
                    db = null
                    records = HashMap()
                }
            }

            // ---- 删除偏好学习（beta）：从「已精选」记录统计功能性标签，后续降低其排序权重 ----
            var tagPenalties: Map<String, Double> = emptyMap()
            if (config.learnPrefer && db != null) {
                try {
                    val works = records.values.map {
                        PreferenceLearner.WorkTags(
                            it.tags, it.status == "pruned", it.pageCount, it.files.size,
                        )
                    }
                    val stats = PreferenceLearner.buildStats(works)
                    tagPenalties = PreferenceLearner.penalties(stats, PREFER_MIN_SEEN)
                    if (tagPenalties.isNotEmpty()) {
                        val desc = tagPenalties.entries.sortedByDescending { it.value }.take(6)
                            .joinToString("、") { "${it.key} -${(it.value * 100).toInt()}%" }
                        log("[*] 偏好学习: 统计 ${stats.size} 个功能性标签，降低 ${tagPenalties.size} 个标签的权重（$desc）")
                    } else {
                        log("[*] 偏好学习: 暂无足够删除样本（删掉部分图片后会自动学习）")
                    }
                } catch (e: Exception) {
                    log("[!] 偏好学习失败（本次不启用）: ${e.message}")
                }
            }

            // ---- 搜索 + 详情筛选（不足目标数量时自动扩大扫描） ----
            val searchModes = when {
                config.r18Only -> listOf("r18")
                config.includeR18 -> listOf("all", "r18")
                else -> listOf("all")
            }
            val target = config.maxImages
            val candidates = ArrayList<WorkBrief>()
            val seen = HashSet<String>()
            val pages = HashMap<String, Int>()
            val modeDone = HashMap<String, Boolean>()
            searchModes.forEach {
                pages[it] = 1
                modeDone[it] = false
            }
            val processed = HashSet<String>()
            var skippedDup = 0
            var r18Cand = 0
            var pagesScanned = 0

            val details = ArrayList<WorkDetail>()
            var effMinLikes = config.minLikes              // 允许在「低产提醒」中放宽
            val lowYieldStep = maxOf(LOW_YIELD_BASE, target * 10)
            var warnAsked = false                          // 每次运行只提醒一次
            val lowLikesDetails = ArrayList<WorkDetail>()  // 仅因点赞不足被过滤（放宽后从这里补回）
            val penCache = HashMap<String, Pair<Double, String>>()  // id -> (偏好削减, 命中标签)
            var lowCount = 0
            var aiCount = 0
            var noImgCount = 0
            var r18SkipCount = 0
            var safeSkipCount = 0
            var nicheCount = 0

            val modeLabel = when {
                config.r18Only -> "仅R18"
                !config.includeR18 -> "不含R18"
                else -> "含R18"
            }

            log("")
            log("[*] 搜索作品...")
            log("[*] 获取详情 (≥${config.minLikes}赞, 过滤AI, $modeLabel)...")
            onPhase?.invoke("正在搜索作品…")

            // 关键：筛选出的作品不足目标数量时，继续翻页扩大扫描，直到满足 / 搜索穷尽
            while (details.size < target) {
                // ---- 补充候选：保证「未处理候选」至少有目标数量（每个 mode 至少翻过第 1 页）----
                for (mode in searchModes) {
                    while ((candidates.size - processed.size < target || pages[mode] == 1) &&
                        modeDone[mode] != true && (pages[mode] ?: 1) <= 50
                    ) {
                        if (isStopped()) return RunResult(false, reason = "用户停止")
                        val page = pages[mode] ?: 1
                        val items = PixivApi.search(config.tag, config.order, page, mode)
                        pagesScanned++
                        if (items.isEmpty()) {
                            log("  [$mode] 第 $page 页无结果，结束")
                            modeDone[mode] = true
                            break
                        }
                        var added = 0
                        var dup = 0
                        for (it in items) {
                            if (!seen.add(it.id)) continue
                            val rec = records[it.id]
                            if (config.dedup && rec != null &&
                                shouldSkip(rec, index, db, config.dedupSkipFiltered, it.illustType == 2)
                            ) {
                                dup++
                                skippedDup++
                                continue
                            }
                            candidates.add(it)
                            if (it.xRestrict > 0) r18Cand++
                            added++
                        }
                        if (added > 0 || dup > 0) {
                            log("  [$mode] 第 $page 页: 新增 $added，查重跳过 $dup（累计新 ${candidates.size}，共跳过 $skippedDup）")
                        } else {
                            modeDone[mode] = true // 整页都是已见过的条目（翻页未生效），避免死循环
                            break
                        }
                        onPhase?.invoke("正在搜索 · 第 $page 页 · 候选 ${candidates.size} 个")
                        pages[mode] = page + 1
                        delay(apiDelayMs)
                    }
                }

                // ---- 详情筛选：处理所有尚未筛选的候选 ----
                for (c in candidates) {
                    if (!processed.add(c.id)) continue
                    if (isStopped()) return RunResult(false, reason = "用户停止")
                    val d = PixivApi.detail(c.id)
                    if (d == null) {
                        noImgCount++
                        log("  [${processed.size}/${candidates.size}] ${c.id}: ${c.title.take(30)} x 无图")
                        delay(apiDelayMs)
                        continue
                    }
                    val likes = d.likeCount
                    val imgNum = d.imageUrls.size
                    val label: String
                    when {
                        effMinLikes > 0 && likes < effMinLikes -> {
                            lowCount++
                            label = "x ${likes}赞"
                            lowLikesDetails.add(d)
                            safeCall { db?.upsertFiltered(d, "low_likes") }
                        }
                        imgNum == 0 -> {
                            noImgCount++
                            label = "x 无图" // 不写入查重库: 可能是临时失败, 下次重试
                        }
                        config.filterAi && hasAiTag(d.tags) -> {
                            aiCount++
                            label = "x AI"
                            safeCall { db?.upsertFiltered(d, "ai") }
                        }
                        !config.includeR18 && d.isR18 -> {
                            r18SkipCount++
                            label = "x R18"
                            safeCall { db?.upsertFiltered(d, "r18_excluded") }
                        }
                        config.r18Only && !d.isR18 -> {
                            safeSkipCount++
                            label = "x 非R18"
                            safeCall { db?.upsertFiltered(d, "safe_excluded") }
                        }
                        d.isR18 && config.filterNicheR18 &&
                            NicheFetishes.blocked(d.tags, config.allowedNiche) -> {
                            nicheCount++
                            label = "x 性癖"
                            // 不写入查重库：调整「允许的性癖」后下次运行可以重新尝试
                        }
                        else -> {
                            val (pen, penTag) = PreferenceLearner.workPenalty(d.tags, tagPenalties)
                            penCache[d.id] = pen to penTag
                            label = if (pen > 0) {
                                "OK likes$likes ${imgNum}图 偏好-${(pen * 100).toInt()}%($penTag)"
                            } else {
                                "OK likes$likes ${imgNum}图"
                            }
                            details.add(d)
                        }
                    }
                    log("  [${processed.size}/${candidates.size}] ${c.id}: ${d.title.take(30)} $label")
                    onPhase?.invoke("正在筛选详情 ${processed.size}/${candidates.size} · 已通过 ${details.size}")
                    delay(apiDelayMs)

                    // ---- 低产提醒：检查了很多详情仍凑不够目标 → 询问是否放宽点赞条件（每次运行只提醒一次）----
                    if (!warnAsked && effMinLikes > 0 && lowLikesDetails.isNotEmpty() &&
                        details.size < target && processed.size >= lowYieldStep
                    ) {
                        val allLikes = ArrayList<Long>(details.size + lowLikesDetails.size)
                        details.forEach { allLikes.add(it.likeCount) }
                        lowLikesDetails.forEach { allLikes.add(it.likeCount) }
                        val sug = lowYieldSuggest(allLikes, target, effMinLikes)
                        if (sug.suggested < effMinLikes) {
                            warnAsked = true
                            log("  [!] 效率提醒：已检查 ${processed.size} 个作品，仅 ${details.size}/$target 个满足「≥$effMinLikes 赞」")
                            log("      建议：放宽到 ≥${sug.suggested} 赞（样本 ${sug.sample} 个中约有 ${sug.est} 个符合）")
                            val ask = LowYieldAsk(
                                RunState.nextAskId(), processed.size, details.size, target,
                                effMinLikes, sug.suggested, sug.est, sug.sample,
                            )
                            RunState.newAsk(ask)
                            var waited = 0L
                            while (RunState.pendingAsk?.id == ask.id && !isStopped() &&
                                waited < ASK_TIMEOUT_MS
                            ) {
                                delay(250)
                                waited += 250
                            }
                            val choice = if (RunState.pendingAsk?.id == ask.id) {
                                // 超时 / 被停止：清理询问并按「继续查找」处理
                                RunState.answerAsk(ask.id, RunState.ASK_CONTINUE)
                                log("[*] 未收到选择（超时/停止），按「继续查找」处理")
                                RunState.ASK_CONTINUE
                            } else if (RunState.lastAnswerId == ask.id) {
                                RunState.lastAnswerChoice
                            } else {
                                RunState.ASK_CONTINUE
                            }
                            if (choice == RunState.ASK_LOWER) {
                                var moved = 0
                                val it2 = lowLikesDetails.iterator()
                                while (it2.hasNext()) {
                                    val w = it2.next()
                                    if (w.likeCount >= sug.suggested) {
                                        details.add(w)
                                        it2.remove()
                                        moved++
                                    }
                                }
                                lowCount = (lowCount - moved).coerceAtLeast(0)
                                effMinLikes = sug.suggested
                                log("  [*] 已放宽最低点赞 → ${sug.suggested} 赞：从已扫描作品中补入 $moved 个（当前 ${details.size}/$target）")
                                if (details.size >= target) break
                            } else {
                                log("  [*] 继续按原条件查找")
                            }
                        }
                    }

                    if (lowCount >= 20 && details.size >= target) {
                        log("  [*] 足够候选，停止详情获取")
                        break
                    }
                    if (details.size >= target * 3) {
                        log("  [*] 候选充足，停止详情获取")
                        break
                    }
                }

                if (details.size >= target) break
                if (searchModes.all { modeDone[it] == true || (pages[it] ?: 1) > 50 }) {
                    log("[*] 搜索结果已穷尽（共扫描 $pagesScanned 页）")
                    break
                }
            }

            if (skippedDup > 0) log("[*] 查重: 跳过 $skippedDup 个已处理过的作品")
            log("")
            log("[*] 共收集 ${candidates.size} 个作品（其中标记 R18 的 $r18Cand 个），已扫描 $pagesScanned 页")
            if (candidates.isEmpty()) {
                if (skippedDup > 0) {
                    log("[*] 没有新作品：搜索范围内的作品都已处理过（查重跳过）")
                    return RunResult(true, downloaded = 0, skippedDup = skippedDup)
                }
                log("[!] 无结果，请检查标签名、VPN 和登录状态")
                return RunResult(false, reason = "无搜索结果")
            }
            if (config.includeR18 && !config.r18Only && r18Cand == 0) {
                log("[!] 搜索结果里没有 R18 作品：可能是未登录（游客），")
                log("    或账号未开启「R-18作品の表示」（设置 → 閲覧設定）。")
            }

            log("")
            log("[*] 低赞:$lowCount AI:$aiCount 无图:$noImgCount R18跳过:$r18SkipCount 非R18跳过:$safeSkipCount 性癖:$nicheCount -> 有效:${details.size}")
            if (details.size < target) {
                log("[!] 满足条件的作品只有 ${details.size} 个（目标 $target）：已尽力扩大扫描，可尝试降低最低点赞或关闭 AI 过滤")
            }
            if (details.isEmpty()) {
                log("[*] 没有符合条件的作品可下载")
                return RunResult(true, downloaded = 0, skippedDup = skippedDup)
            }

            val strength = config.preferStrength.coerceIn(0, 100) / 100.0
            details.sortWith(
                compareByDescending<WorkDetail> {
                    it.likeCount * (1 - strength * (penCache[it.id]?.first ?: 0.0))
                }.thenByDescending { it.likeCount }
            )
            val lowered = details.count { (penCache[it.id]?.first ?: 0.0) > 0.0 }
            if (lowered > 0 && strength > 0) {
                log("[*] 偏好排序: $lowered 个作品因删除偏好下调权重（强度 ${config.preferStrength}%）")
            }
            val ls = details.map { it.likeCount }
            log("[*] 点赞范围: ${ls.min()} ~ ${ls.max()}, 平均: ${ls.sum() / ls.size}")

            // ---- 下载阶段 ----
            log("")
            log("[*] 开始下载...")
            onDownloadStart?.invoke()
            var dl = 0
            val meta = ArrayList<DownloadMeta>()

            for (d in details) {
                if (isStopped()) {
                    log("[*] 用户停止下载")
                    break
                }
                if (dl >= config.maxImages) break
                if (d.isR18 && !config.includeR18) continue
                if (!d.isR18 && config.r18Only) continue

                // 文件夹含标签名：<标签>-safe / <标签>-r18（相册里每个文件夹都能看出归属）
                val relFolder = "${sanitize(config.tag)}-${if (d.isR18) "r18" else "safe"}"

                // 旧文件索引：点赞数变化会导致文件名变化，按页号沿用旧文件
                val oldByPage = HashMap<Int, String>()
                records[d.id]?.files?.forEach { f ->
                    val m = Regex("_" + Regex.escape(d.id) + "_p(\\d+)_").find(File(f).name)
                    if (m != null) oldByPage[m.groupValues[1].toInt()] = f
                }

                var ok = 0
                val saved = ArrayList<String>()
                val totalPages = if (d.isUgoira) 1 else d.imageUrls.size

                if (d.isUgoira) {
                    // 动图：先下载帧序列 zip，再合成为可播放的 GIF
                    val key = convertUgoira(d, relFolder, store, log, isStopped, onPhase)
                    if (key != null) {
                        ok = 1
                        saved.add(key)
                        index.add(key)
                    }
                } else {
                    for ((j, imgUrl) in d.imageUrls.withIndex()) {
                        if (isStopped()) break
                        val ext = extOf(imgUrl)
                        val likesStr = d.likeCount.toString().padStart(8, '0')
                        val name = "${likesStr}_${d.id}_p${j}_${sanitize(d.author)}$ext"
                        val curKey = store.keyFor(relFolder, name)
                        if (curKey in index) {
                            ok++
                            saved.add(curKey)
                            continue
                        }
                        val oldKey = oldByPage[j]
                        if (oldKey != null && oldKey in index) {
                            ok++
                            saved.add(oldKey)
                            continue
                        }
                        val bytes = PixivApi.downloadImage(imgUrl)
                        if (bytes != null) {
                            val key = store.save(relFolder, name, mimeOf(ext), bytes)
                            if (key != null) {
                                ok++
                                saved.add(key)
                                index.add(key)
                            }
                        }
                        delay(downloadDelayMs)
                    }
                }

                if (config.dedup && ok > 0) {
                    safeCall { db?.upsertDownloaded(d, relFolder, saved) }
                }

                if (ok > 0) {
                    dl++
                    onProgress?.invoke(dl)
                    val tagStr = if (d.isR18) " [R18]" else " [safe]"
                    val kindStr = if (d.isUgoira) " [动图]" else ""
                    log("  [$dl/${config.maxImages}]$tagStr$kindStr likes ${d.likeCount} ${d.title.take(40)} | $ok/$totalPages")
                    meta.add(DownloadMeta(d.id, d.title, d.author, d.likeCount, ok, totalPages))
                }
                if (dl >= config.maxImages) break
            }

            log("")
            log("=".repeat(56))
            log("  完成！下载 $dl 个新作品")
            if (skippedDup > 0) log("  查重跳过 $skippedDup 个已处理过的作品")
            log("  保存位置：相册 Pictures/PixivScraper/${sanitize(config.tag)}-safe（或 -r18）")
            log("=".repeat(56))
            if (meta.isNotEmpty()) {
                log("  Top 10:")
                meta.take(10).forEachIndexed { idx, m ->
                    log("  ${idx + 1}. likes ${m.likes}  ${m.title.take(45)}  by ${m.author}")
                }
            }
            return RunResult(true, downloaded = dl, skippedDup = skippedDup)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[!] 错误: ${e.message}")
            return RunResult(false, reason = e.message ?: "未知错误")
        } finally {
            safeCall { db?.close() }
        }
    }

    // ---------------- 查重逻辑（与桌面版一致） ----------------

    private data class ReconcileResult(val toMissing: Int, val toPruned: Int, val toDone: Int)

    /**
     * 对账：同步被手动删除/恢复的文件状态。
     * - 整个作品全删 → missing（下次重新下载）
     * - 只删了一部分 → pruned（视为有意保留，不再补下）
     * - 文件恢复齐全 → downloaded
     */
    private fun reconcile(
        records: Map<String, HistoryDb.WorkRecord>,
        index: Set<String>,
        db: HistoryDb,
    ): ReconcileResult {
        var toMissing = 0
        var toPruned = 0
        var toDone = 0
        for (rec in records.values) {
            if (rec.status != "downloaded" && rec.status != "missing" && rec.status != "pruned") continue
            val newStatus = evalStatus(rec, index)
            if (newStatus == rec.status) continue
            rec.status = newStatus
            if (newStatus == "pruned") {
                rec.files = rec.files.filter { it in index }
                safeCall { db.setFiles(rec.id, rec.files) }
            }
            safeCall { db.setStatus(rec.id, newStatus) }
            when (newStatus) {
                "missing" -> toMissing++
                "pruned" -> toPruned++
                else -> toDone++
            }
        }
        return ReconcileResult(toMissing, toPruned, toDone)
    }

    /** 删除行为判断：全删 → missing；部分删 → pruned；齐全 → downloaded */
    private fun evalStatus(rec: HistoryDb.WorkRecord, index: Set<String>): String {
        val files = rec.files
        if (files.isEmpty()) return "missing"
        val present = files.count { it in index }
        return when {
            present == 0 -> "missing"
            present < files.size -> "pruned"
            files.size >= max(1, rec.pageCount) -> "downloaded"
            // 文件数本来就少于页数：之前被标记为 pruned（用户删掉了多余的页）则保持 pruned
            rec.status == "pruned" -> "pruned"
            else -> "missing"
        }
    }

    private fun shouldSkip(
        rec: HistoryDb.WorkRecord,
        index: Set<String>,
        db: HistoryDb?,
        skipFiltered: Boolean,
        isUgoira: Boolean = false,
    ): Boolean = when (rec.status) {
        "downloaded" -> {
            // 动图：旧版本可能只存了静态首帧（非 .gif），视为未完成 → 重新下载
            if (isUgoira && rec.files.none { it.endsWith(".gif", ignoreCase = true) }) {
                rec.status = "missing"
                safeCall { db?.setStatus(rec.id, "missing") }
                false
            } else if (isComplete(rec, index)) {
                true
            } else {
                // 文件已被删除 → 更新表, 当作未处理
                rec.status = "missing"
                safeCall { db?.setStatus(rec.id, "missing") }
                false
            }
        }
        "pruned" -> true // 用户主动删了一部分（视为有意筛选）→ 不再补下；全删时对账会变回 missing
        "filtered" -> skipFiltered
        else -> false
    }

    // ---------------- 动图（ugoira）合成 ----------------

    /**
     * 下载动图并合成为 GIF：
     * 1. ugoira_meta 取帧序列 zip（优先 pixiv 播放版 600px）
     * 2. 流式下载到缓存目录（不整包驻留内存）
     * 3. 逐帧解码（长边封顶 600）→ GifEncoder 合成（延迟按 pixiv 元数据）
     * 成功返回保存后的查重 key；失败返回 null
     */
    private suspend fun convertUgoira(
        d: WorkDetail,
        relFolder: String,
        store: ImageStore,
        log: (String) -> Unit,
        isStopped: () -> Boolean,
        onPhase: ((String) -> Unit)?,
    ): String? {
        val meta = PixivApi.ugoiraMeta(d.id)
        if (meta == null) {
            log("  [动图] 元信息获取失败，跳过")
            return null
        }
        val tmp = File(context.cacheDir, "ugoira_${d.id}.zip")
        log("  [动图] 下载动图包（${meta.frames.size} 帧）...")
        if (!PixivApi.downloadToFile(meta.zipUrl, tmp) || tmp.length() < 1000L) {
            log("  [动图] 动图包下载失败")
            tmp.delete()
            return null
        }
        try {
            val entries = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(BufferedInputStream(FileInputStream(tmp))).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.isDirectory && !e.name.endsWith(".json")) {
                        entries.add(e.name.substringAfterLast('/') to zin.readBytes())
                    }
                    e = zin.nextEntry
                }
            }
            entries.sortBy { it.first }
            if (entries.isEmpty()) {
                log("  [动图] 包里没有可用帧")
                return null
            }
            val delayByFile = HashMap<String, Int>()
            meta.frames.forEach { delayByFile[it.file.substringAfterLast('/')] = it.delayMs }
            val fallbackDelay = meta.frames.firstOrNull()?.delayMs ?: 100

            var enc: GifEncoder? = null
            var encW = 0
            var encH = 0
            var count = 0
            for ((idx, entry) in entries.withIndex()) {
                if (isStopped()) return null
                val bytes = entry.second
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val scaled = scaleForGif(bmp)
                if (enc == null) {
                    encW = scaled.width
                    encH = scaled.height
                    enc = GifEncoder(encW, encH)
                }
                val px = IntArray(encW * encH)
                if (scaled.width == encW && scaled.height == encH) {
                    scaled.getPixels(px, 0, encW, 0, 0, encW, encH)
                } else {
                    val again = Bitmap.createScaledBitmap(scaled, encW, encH, true)
                    again.getPixels(px, 0, encW, 0, 0, encW, encH)
                    if (again !== scaled) again.recycle()
                }
                enc.addFrame(px, delayByFile[entry.first] ?: fallbackDelay)
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
                count++
                onPhase?.invoke("正在合成动图 ${idx + 1}/${entries.size}")
            }
            val encoder = enc
            if (encoder == null || count == 0) {
                log("  [动图] 没有可用帧")
                return null
            }
            val gif = encoder.finish()
            val likesStr = d.likeCount.toString().padStart(8, '0')
            val fileName = "${likesStr}_${d.id}_p0_${sanitize(d.author)}.gif"
            val key = store.save(relFolder, fileName, "image/gif", gif)
            if (key == null) {
                log("  [动图] 保存到相册失败")
                return null
            }
            log("  [动图] 合成完成：$count 帧 ${encW}x$encH · ${gif.size / 1024} KB")
            return key
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("  [动图] 合成失败: ${e.message}")
            return null
        } finally {
            tmp.delete()
        }
    }

    /** 动图帧长边封顶 600（pixiv 播放版分辨率），控制 GIF 体积 */
    private fun scaleForGif(bmp: Bitmap): Bitmap {
        val maxSide = max(bmp.width, bmp.height)
        if (maxSide <= 600) return bmp
        val ratio = 600f / maxSide
        val w = max(1, (bmp.width * ratio).toInt())
        val h = max(1, (bmp.height * ratio).toInt())
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    // ---------------- 低产提醒（建议值估算） ----------------

    private data class LowYieldSuggestion(val suggested: Int, val est: Int, val sample: Int)

    /**
     * 按样本点赞分布估算建议阈值：取第 target 高的点赞值（样本内约 target 个能过），
     * 向下取整到 10；样本不足 target 时退回当前阈值的一半。
     */
    private fun lowYieldSuggest(likesList: List<Long>, target: Int, curMin: Int): LowYieldSuggestion {
        val vals = likesList.sortedDescending()
        val n = vals.size
        val base: Long = if (target > 0 && n >= target) vals[target - 1] else (curMin / 2).toLong()
        var sug = ((base / 10) * 10).toInt()
        if (sug >= curMin) sug = (curMin * 6 / 10) / 10 * 10
        if (sug < 0) sug = 0
        val est = vals.count { it >= sug }
        return LowYieldSuggestion(sug, est, n)
    }

    private fun isComplete(rec: HistoryDb.WorkRecord, index: Set<String>): Boolean {
        val files = rec.files
        if (files.isEmpty() || files.size < max(1, rec.pageCount)) return false
        return files.all { it in index }
    }

    // ---------------- 工具 ----------------

    private fun safeCall(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private val aiTags = setOf(
        "ai", "ai生成", "aiイラスト", "aiart", "aiアート", "aiillust",
        "novelai", "stablediffusion", "sdxl", "midjourney", "dall-e",
        "ai-generated", "generated by ai", "ai生成作品",
    )

    private fun hasAiTag(tags: List<String>): Boolean {
        if (tags.isEmpty()) return false
        return tags.any { t ->
            val tl = t.lowercase()
            aiTags.any { ai -> tl == ai || tl.contains(ai) }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    private fun extOf(url: String): String {
        val clean = url.substringBefore('?')
        val dot = clean.lastIndexOf('.')
        if (dot < 0) return ".jpg"
        val ext = clean.substring(dot).lowercase()
        return if (ext in setOf(".jpg", ".jpeg", ".png", ".gif", ".webp")) ext else ".jpg"
    }

    private fun mimeOf(ext: String): String = when (ext) {
        ".png" -> "image/png"
        ".gif" -> "image/gif"
        ".webp" -> "image/webp"
        else -> "image/jpeg"
    }
}
