package com.pixivscraper

/**
 * 删除偏好学习（beta）：
 * 从「已精选(pruned，用户删掉了一部分)」的查重记录中统计功能性标签，
 * 计算每个标签的删除比例；下载排序时按比例降低其权重（不会直接排除）。
 *
 * 两个防误判措施：
 *  1) 按搜索标签分上下文：同一个标签在不同搜索标签下含义可能相反（搜索贫乳角色时删掉「巨乳」版本，
 *     搜索巨乳角色时删掉「贫乳」版本）——各学各的，互不干扰。
 *  2) 排除基础标签：与搜索标签高度伴随的标签（如贫乳角色下的「贫乳」）视为该标签的基础特征，不参与学习。
 *
 * 只统计 [FUNC_TAGS] 里的功能/内容标签 —— 角色名、作品名、系列名等身份标签
 * 不在词库中，因此不会参与学习。词库可按需增删。
 */
object PreferenceLearner {

    /** 与搜索标签高度伴随的判定阈值：出现比例 ≥ 该值 → 视为「基础标签」，不参与学习 */
    const val ASSOC_RATIO = 0.6

    /** 功能性标签词库：分类名 -> 标签列表（pixiv 常用日文为主，附常见英文/中文写法） */
    val FUNC_TAGS: Map<String, List<String>> = linkedMapOf(
        "体型 / 胸部" to listOf(
            "巨乳", "爆乳", "超乳", "デカパイ", "でかぱい", "貧乳", "贫乳", "微乳", "無乳",
            "ちっぱい", "ぺったんこ", "おっぱい", "パイズリ", "母乳", "谷間", "boobs", "bigboobs",
        ),
        "体型 / 其他" to listOf(
            "ふともも", "太もも", "魅惑のふともも", "お尻", "尻", "巨尻", "腹筋", "筋肉",
            "筋肉娘", "ぽっちゃり", "小柄", "長身", "陰毛", "すじ",
        ),
        "服装 / 制服系" to listOf(
            "制服", "セーラー服", "体操服", "ブルマ", "スク水", "スクール水着", "ナース",
            "メイド", "巫女", "シスター", "チャイナ服", "着物", "浴衣", "レオタード",
            "全身タイツ", "ボンテージ",
        ),
        "服装 / 内衣泳装" to listOf(
            "水着", "競泳水着", "ビキニ", "マイクロビキニ", "下着", "ランジェリー", "ブラジャー",
            "ぱんつ", "パンツ", "ノーパン", "ノーブラ", "ストッキング", "ニーソ", "パンスト",
            "手袋", "バニーガール", "裸エプロン", "パンチラ",
        ),
        "风格 / 形式" to listOf(
            "全彩", "フルカラー", "漫画", "コミック", "モノクロ", "線画", "ラフ", "3DCG", "CG",
            "コイカツ", "コイカツ!", "Koikatsu", "Ugoira", "うごイラ", "动图", "動画", "アニメ",
            "イラスト",
        ),
        "行为 / 性交" to listOf(
            "中出し", "外出し", "顔射", "口内射精", "ぶっかけ", "フェラ", "フェラチオ", "手コキ",
            "足コキ", "素股", "3P", "乱交", "複数プレイ", "ハーレム", "アナル", "後背位",
            "騎乗位", "オナニー", "潮吹き", "強制絶頂", "絶頂",
        ),
        "束缚 / 调教" to listOf(
            "拘束", "縛り", "緊縛", "調教", "SM", "BDSM", "首輪", "目隠し", "ローター",
            "バイブ", "痴漢", "催眠",
        ),
        "表情 / 反应" to listOf("アヘ顔", "無様エロ", "涙目"),
        "年龄感 / 学生" to listOf(
            "ロリ", "ロリコン", "loli", "萝莉", "ショタ", "ショタコン", "shota", "shotacon",
            "学生", "JK", "女子高生",
        ),
        "孕期 / 腹部" to listOf(
            "妊娠", "妊婦", "孕ませ", "子作り", "出産", "授乳", "ボテ腹", "腹ボコ",
            "inflation", "bodyinflation", "bloated",
        ),
        "变身 / 置换" to listOf("性転換", "女体化", "男体化", "TSF", "入れ替わり", "乗っ取り", "皮モノ"),
        "关系 / 情境" to listOf(
            "ラブラブ", "イチャラブ", "恋人", "新婚", "純愛", "人妻", "熟女", "MILF", "ギャル",
            "ビッチ", "痴女", "お姉さん", "巨根", "近親相姦", "incest",
        ),
        "癖好（含少量小众）" to listOf(
            "ふたなり", "NTR", "寝取られ", "寝取らせ", "触手", "獣姦", "機械姦", "リョナ",
        ),
    )

    private val lookup: Map<String, String> = HashMap<String, String>().apply {
        FUNC_TAGS.values.forEach { list -> list.forEach { put(it.trim().lowercase(), it) } }
    }

    /** 一条查重记录里与学习相关的信息 */
    data class WorkTags(
        val tags: List<String>,
        val pruned: Boolean,
        val pageCount: Int = 1,
        val remainFiles: Int = 0,
        /** 该作品是在哪个搜索标签下处理的（偏好按搜索标签分上下文学习） */
        val sourceTag: String = "",
    )

    /** 标签的「被下载 / 被用户删除」计数（删除按页数比例计权） */
    class Counts(var seen: Int = 0, var pruned: Double = 0.0)

    /**
     * 某个搜索标签下的统计结果。
     * [baseline] = 与该搜索标签高度伴随的标签（如贫乳角色下的「贫乳」），不参与学习。
     */
    data class ContextStats(
        val counts: Map<String, Counts>,
        val baseline: Set<String>,
        val total: Int,
    )

    /**
     * 按搜索标签统计：只统计 [WorkTags.sourceTag] 等于 [contextTag] 的记录。
     * [contextTag] 为空时统计全部（旧版行为）。
     */
    fun buildContextStats(
        works: List<WorkTags>,
        contextTag: String,
        assocRatio: Double = ASSOC_RATIO,
    ): ContextStats {
        val stats = LinkedHashMap<String, Counts>()
        val tagWorks = LinkedHashMap<String, Int>()
        var total = 0
        for (w in works) {
            if (contextTag.isNotEmpty() && w.sourceTag != contextTag) continue
            total++
            val keys = LinkedHashSet<String>()
            for (t in w.tags) {
                lookup[t.trim().lowercase()]?.let { keys.add(it) }
            }
            for (k in keys) {                     // 伴随比例：只要出现过就算
                tagWorks[k] = (tagWorks[k] ?: 0) + 1
            }
            if (keys.isEmpty()) continue
            var weight = 0.0
            if (w.pruned) {
                val totalPages = if (w.pageCount > 0) w.pageCount else 1
                weight = ((totalPages - w.remainFiles).toDouble() / totalPages).coerceIn(0.0, 1.0)
                if (weight <= 0.0) weight = 1.0   // 兜底：状态为已精选但页数信息缺失
            }
            for (k in keys) {
                val c = stats.getOrPut(k) { Counts() }
                c.seen++
                c.pruned += weight
            }
        }
        val baseline = LinkedHashSet<String>()
        if (total > 0) {
            for ((tag, n) in tagWorks) {
                if (n.toDouble() / total >= assocRatio) baseline.add(tag)
            }
        }
        return ContextStats(stats, baseline, total)
    }

    /** 标签的删除比例（0-1）；样本不足、从未被删、或属于基础标签的不参与 */
    fun penalties(
        stats: Map<String, Counts>,
        minSeen: Int,
        baseline: Set<String> = emptySet(),
    ): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for ((tag, c) in stats) {
            if (tag in baseline) continue          // 与搜索标签高度伴随 → 视为基础标签
            if (c.pruned <= 0.0 || c.seen < maxOf(1, minSeen)) continue
            out[tag] = minOf(1.0, c.pruned / c.seen)
        }
        return out
    }

    /** 作品命中的最大删除比例 + 对应标签（无命中返回 0 / ""） */
    fun workPenalty(tags: List<String>, penalties: Map<String, Double>): Pair<Double, String> {
        var best = 0.0
        var bestTag = ""
        if (penalties.isEmpty()) return 0.0 to ""
        for (t in tags) {
            val tag = lookup[t.trim().lowercase()] ?: continue
            val r = penalties[tag] ?: 0.0
            if (r > best) {
                best = r
                bestTag = tag
            }
        }
        return best to bestTag
    }
}
