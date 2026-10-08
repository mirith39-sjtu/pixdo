package com.pixivscraper

/**
 * R18 小众性癖标签库（与桌面版 pixiv_scraper.py 的 NICHE_FETISHES 同源）。
 * 用于「过滤小众性癖」：命中未被允许的类别时，该作品不下载。
 */
object NicheFetishes {

    /** (类别 key, 显示名, 命中标签) —— 标签以 pixiv 常用日文 tag 为主，附常用英文 */
    val CATEGORIES: List<Triple<String, String, List<String>>> = listOf(
        Triple("bestiality", "人兽 / 兽交", listOf("獣姦", "人獣", "ケモ姦", "bestiality")),
        Triple("futanari", "扶她（ふたなり）", listOf("ふたなり", "ふたなりっ娘", "futanari")),
        Triple("tentacle", "触手", listOf("触手", "触手責め", "テンタクル", "tentacle")),
        Triple("monster", "异种 / 怪物", listOf("異種姦", "オーク", "ゴブリン", "モンスター姦")),
        Triple("machine", "机械 / 器具", listOf("機械姦", "機械化", "マシーン")),
        Triple("kemono", "兽人 / 毛茸茸", listOf("ケモナー", "ケモノ", "獣人", "オスケモ", "メスケモ", "furry")),
        Triple("crossdress", "伪娘 / 女装", listOf("男の娘", "女装", "crossdressing")),
        Triple("genderbend", "性转 / 变身", listOf("性転換", "TSF", "女体化", "男体化")),
        Triple("ntr", "NTR / 寝取られ", listOf("寝取られ", "NTR", "寝取らせ")),
        Triple("ryona", "猎奇 / リョナ", listOf("リョナ", "グロ", "R-18G")),
        Triple("scat", "排泄系", listOf("スカトロ", "放尿", "おもらし", "排泄")),
        Triple("loli", "幼态（ロリ / ショタ）", listOf("ロリ", "ロリコン", "ショタ", "ショタコン")),
        Triple("bl", "BL / ホモ", listOf("BL", "ホモ", "腐向け")),
        Triple("extreme", "扩张 / 极端玩法", listOf("尿道", "アナル拡張", "フィスト", "拡張姦")),
    )

    private val byKey: Map<String, String> = CATEGORIES.associate { it.first to it.second }

    fun labelOf(key: String): String = byKey[key] ?: key

    /**
     * 返回标签命中的类别 key 列表。
     *
     * 匹配规则：大小写不敏感；日文/中文标签允许「包含」匹配（如 獣姦えっち），
     * 纯英文标签只做精确匹配（避免 BL 误伤 Blood 之类）。
     */
    fun hits(tags: List<String>): List<String> {
        if (tags.isEmpty()) return emptyList()
        val norm = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (norm.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for ((key, _, kws) in CATEGORIES) {
            val hit = norm.any { t ->
                kws.any { k ->
                    val kl = k.lowercase()
                    t == kl || (!kl.all { c -> c.code < 128 } && kl in t)
                }
            }
            if (hit) out.add(key)
        }
        return out.toList()
    }

    /** 是否命中「未允许」的小众性癖（调用方需保证只对 R18 作品使用） */
    fun blocked(tags: List<String>, allowed: List<String>): Boolean =
        hits(tags).any { it !in allowed }
}
