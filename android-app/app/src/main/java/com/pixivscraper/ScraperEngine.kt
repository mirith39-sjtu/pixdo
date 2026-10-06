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
                    val filtered = records.values.count { it.status == "filtered" }
                    log("[*] 查重库: 共 ${records.size} 条（已下载 $downloaded / 文件缺失 $missing / 已过滤 $filtered）")
                    val changed = reconcile(records, index, d)
                    if (changed > 0) log("[*] 查重: $changed 条记录的文件被删除/恢复，已更新状态")
                } catch (e: Exception) {
                    log("[!] 查重库打开失败（本次不做去重）: ${e.message}")
                    db = null
                    records = HashMap()
                }
            }

            // ---- 搜索阶段 ----
            val searchModes = when {
                config.r18Only -> listOf("r18")
                config.includeR18 -> listOf("all", "r18")
                else -> listOf("all")
            }
            val candLimit = config.maxImages * 5
            val candidates = ArrayList<WorkBrief>()
            val seen = HashSet<String>()
            var skippedDup = 0
            var r18Cand = 0

            log("")
            log("[*] 搜索作品...")
            for (mode in searchModes) {
                var page = 1
                while (candidates.size < candLimit && page <= 50) {
                    if (isStopped()) return RunResult(false, reason = "用户停止")
                    val items = PixivApi.search(config.tag, config.order, page, mode)
                    if (items.isEmpty()) {
                        log("  [$mode] 第 $page 页无结果，结束")
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
                        break // 整页都是本轮已见过的条目（翻页未生效），避免死循环
                    }
                    onPhase?.invoke("正在搜索 · 第 $page 页 · 候选 ${candidates.size} 个")
                    page++
                    delay(apiDelayMs)
                }
            }

            if (skippedDup > 0) log("[*] 查重: 跳过 $skippedDup 个已处理过的作品")
            log("")
            log("[*] 共收集 ${candidates.size} 个作品（其中标记 R18 的 $r18Cand 个）")
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

            // ---- 详情阶段 ----
            log("")
            val modeLabel = when {
                config.r18Only -> "仅R18"
                !config.includeR18 -> "不含R18"
                else -> "含R18"
            }
            log("[*] 获取详情 (≥${config.minLikes}赞, 过滤AI, $modeLabel)...")
            onPhase?.invoke("正在筛选详情 0/${candidates.size}")
            val details = ArrayList<WorkDetail>()
            var lowCount = 0
            var aiCount = 0
            var noImgCount = 0
            var r18SkipCount = 0
            var safeSkipCount = 0

            for ((i, c) in candidates.withIndex()) {
                if (isStopped()) return RunResult(false, reason = "用户停止")
                val d = PixivApi.detail(c.id)
                if (d == null) {
                    noImgCount++
                    log("  [${i + 1}/${candidates.size}] ${c.id}: ${c.title.take(30)} x 无图")
                    delay(apiDelayMs)
                    continue
                }
                val likes = d.likeCount
                val imgNum = d.imageUrls.size
                val label: String
                when {
                    config.minLikes > 0 && likes < config.minLikes -> {
                        lowCount++
                        label = "x ${likes}赞"
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
                    else -> {
                        label = "OK likes$likes ${imgNum}图"
                        details.add(d)
                    }
                }
                log("  [${i + 1}/${candidates.size}] ${c.id}: ${d.title.take(30)} $label")
                onPhase?.invoke("正在筛选详情 ${i + 1}/${candidates.size} · 已通过 ${details.size}")
                delay(apiDelayMs)
                if (lowCount >= 20 && details.size >= config.maxImages) {
                    log("  [*] 足够候选，停止详情获取")
                    break
                }
                if (details.size >= config.maxImages * 3) {
                    log("  [*] 候选充足，停止详情获取")
                    break
                }
            }

            log("")
            log("[*] 低赞:$lowCount AI:$aiCount 无图:$noImgCount R18跳过:$r18SkipCount 非R18跳过:$safeSkipCount -> 有效:${details.size}")
            if (details.isEmpty()) {
                log("[*] 没有符合条件的作品可下载")
                return RunResult(true, downloaded = 0, skippedDup = skippedDup)
            }

            details.sortByDescending { it.likeCount }
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

    private fun reconcile(
        records: Map<String, HistoryDb.WorkRecord>,
        index: Set<String>,
        db: HistoryDb,
    ): Int {
        var changed = 0
        for (rec in records.values) {
            if (rec.status != "downloaded" && rec.status != "missing") continue
            val newStatus = if (isComplete(rec, index)) "downloaded" else "missing"
            if (newStatus != rec.status) {
                rec.status = newStatus
                safeCall { db.setStatus(rec.id, newStatus) }
                changed++
            }
        }
        return changed
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
