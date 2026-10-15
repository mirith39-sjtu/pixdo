package com.pixivscraper

/**
 * 作品下载后的后处理（与桌面版 pixiv_scraper.postprocess_work 对齐）：
 * - 作品内查重：完全重复（MD5）删除；近似重复（dHash 感知哈希）可开关
 * - 疑似预告 / 广告图：末页横向、与其余页比例不符（模糊作辅助）时猜测删除
 *
 * 本文件只包含纯计算与判定逻辑（不依赖 Android API），便于单元测试。
 */
object WorkPostProcess {

    /** 近似重复：dHash 汉明距离上限（64 位） */
    const val NEAR_HASH_MAX = 6

    /** 近似重复：平均色差上限（0-255）；超过视为换色变体，不删 */
    const val NEAR_COLOR_MAX = 10.0

    /** 预告图：明显横向判定（w/h） */
    const val TRAILER_ASPECT = 1.2

    /** 预告图：正文中竖向页的最低占比 */
    const val TRAILER_PORTRAIT = 0.66

    /** 预告图：相比正文中位数明显模糊的比值上限 */
    const val BLUR_RATIO = 0.45

    /** 模糊度基准值（64×64 灰度的拉普拉斯方差） */
    const val BLUR_BASE = 8.0

    /** 单页指纹（无法解析的图片用 [UNKNOWN]，永不参与删除） */
    data class Sig(
        val md5: String?,
        val w: Int,
        val h: Int,
        val hash: Long?,
        val meanR: Double,
        val meanG: Double,
        val meanB: Double,
        val blur: Double?,
    ) {
        companion object {
            val UNKNOWN = Sig(null, 0, 0, null, 0.0, 0.0, 0.0, null)
        }
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** 灰度数组 → 64 位 dHash：取 9×8 网格均值，逐行比较相邻格 */
    fun dHashFromGray(gray: IntArray, w: Int, h: Int): Long? {
        if (w < 9 || h < 8 || gray.size < w * h) return null
        val grid = IntArray(9 * 8)
        for (gy in 0 until 8) {
            val y0 = gy * h / 8
            val y1 = maxOf(y0 + 1, (gy + 1) * h / 8)
            for (gx in 0 until 9) {
                val x0 = gx * w / 9
                val x1 = maxOf(x0 + 1, (gx + 1) * w / 9)
                var sum = 0L
                var n = 0
                for (y in y0 until minOf(y1, h)) {
                    val row = y * w
                    for (x in x0 until minOf(x1, w)) {
                        sum += gray[row + x]
                        n++
                    }
                }
                grid[gy * 9 + gx] = if (n > 0) (sum / n).toInt() else 0
            }
        }
        var bits = 0L
        for (gy in 0 until 8) {
            for (gx in 0 until 8) {
                bits = (bits shl 1) or if (grid[gy * 9 + gx] > grid[gy * 9 + gx + 1]) 1L else 0L
            }
        }
        return bits
    }

    /** 灰度数组的拉普拉斯方差（越大越清晰） */
    fun blurFromGray(gray: IntArray, w: Int, h: Int): Double? {
        if (w < 3 || h < 3 || gray.size < w * h) return null
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val lap = 4 * gray[row + x] -
                    gray[row - w + x] - gray[row + w + x] -
                    gray[row + x - 1] - gray[row + x + 1]
                val v = kotlin.math.abs(lap).toDouble()
                sum += v
                sumSq += v * v
                n++
            }
        }
        if (n == 0) return null
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** 近似重复：同尺寸(±1) + dHash 距离 ≤6 + 平均色接近 */
    fun isNearDup(a: Sig, b: Sig): Boolean {
        val ha = a.hash ?: return false
        val hb = b.hash ?: return false
        if (a.w <= 0 || a.h <= 0 || b.w <= 0 || b.h <= 0) return false
        if (kotlin.math.abs(a.w - b.w) > 1 || kotlin.math.abs(a.h - b.h) > 1) return false
        if (hamming(ha, hb) > NEAR_HASH_MAX) return false
        val colorDiff = (kotlin.math.abs(a.meanR - b.meanR) +
            kotlin.math.abs(a.meanG - b.meanG) +
            kotlin.math.abs(a.meanB - b.meanB)) / 3.0
        return colorDiff <= NEAR_COLOR_MAX
    }

    /** 末页是否像「预告 / 广告图」；是则返回原因 */
    fun looksLikeTrailer(last: Sig, body: List<Sig>): String? {
        if (last.w <= 0 || last.h <= 0 || body.isEmpty()) return null
        val aspect = last.w.toDouble() / last.h
        if (aspect < 1.05) return null
        val portrait = body.count { it.h >= it.w }.toDouble() / body.size
        if (aspect >= TRAILER_ASPECT && portrait >= TRAILER_PORTRAIT &&
            body.all { it.h > 0 && it.w > 0 && it.w.toDouble() / it.h < aspect * 0.85 }
        ) {
            return "末页横向 ${last.w}×${last.h}"
        }
        val blurs = body.mapNotNull { it.blur }.sorted()
        if (aspect >= 1.05 && portrait >= 0.8 && blurs.isNotEmpty() && last.blur != null) {
            val median = blurs[blurs.size / 2]
            if (median > BLUR_BASE && last.blur < median * BLUR_RATIO) {
                return "末页横向且明显模糊 ${last.w}×${last.h}"
            }
        }
        return null
    }

    /** 规划要删除的页：返回 下标 → 原因（保留最先出现的那张） */
    fun plan(sigs: List<Sig>, dedupe: Boolean, near: Boolean, trailer: Boolean): Map<Int, String> {
        val drop = LinkedHashMap<Int, String>()
        if (sigs.size < 2 || (!dedupe && !trailer)) return drop
        if (dedupe) {
            val firstSeen = HashMap<String, Int>()
            for (i in sigs.indices) {
                val md5 = sigs[i].md5 ?: continue
                val j = firstSeen[md5]
                if (j == null) firstSeen[md5] = i else drop[i] = "与 p$j 完全重复"
            }
            if (near) {
                val rest = sigs.indices.filter { it !in drop }
                for (pos in rest.indices) {
                    val a = rest[pos]
                    if (a in drop) continue
                    for (b in rest.subList(pos + 1, rest.size)) {
                        if (b in drop) continue
                        if (isNearDup(sigs[a], sigs[b])) drop[b] = "与 p$a 近似重复"
                    }
                }
            }
        }
        val last = sigs.size - 1
        if (trailer && last !in drop) {
            val body = sigs.subList(0, last).filterIndexed { i, _ -> i !in drop }
            val why = looksLikeTrailer(sigs[last], body)
            if (why != null) drop[last] = "疑似预告图（$why）"
        }
        return drop
    }
}
