package com.pixivscraper

/**
 * 标签联想的多层匹配策略（解决中文输入联想不全的问题）。
 *
 * pixiv 官方联想接口 rpc/cps.php 只做「前缀匹配」（匹配 tag 名或中文翻译），实测：
 *   · 百合园          → 仅 1 条（靠翻译前缀命中「百合园圣亚（泳装）」）
 *   · 百合園（繁体）  → 6 条（含 百合園セイア 本体）
 *   · 百合园圣        → 1 条（名字尾部是假名，全名反而命中不了）
 *   · 圣娅 / 凯伊 / 未花 这类中间、尾部片段 → 0 条
 *
 * 因此查询时做「原文 + 简繁变体 + 逐级去尾」，返回后按与输入的公共前缀长度排序；
 * 查过的结果会写入本地池（TagSuggestCache），之后中文片段也能离线命中。
 */
object TagSuggester {

    /** 简体 → 繁体 / 日文汉字（覆盖 ACG 标签里常见字） */
    private const val TRAD_PAIRS =
        "园園亚亞圣聖娅婭樱櫻猫貓爱愛优優龙龍东東学學见見绪緒织織团團结結线線纺紡岛島风風剑劍华華丽麗梦夢觉覺归歸来來乐樂术術书書谁誰语語话話认認让讓说說读讀词詞试試题題页頁贝貝车車马馬鸟鳥鱼魚龟龜齐齊仓倉们們个個为為无無义義实實参參双雙欢歡观觀劝勸对對时時树樹极極构構标標样樣机機权權边邊传傳价價众眾会會体體儿兒关關兴興军軍农農冲衝决決况況减減凤鳳划劃则則刚剛创創动動劳勞势勢区區医醫卫衛发發变變号號叶葉听聽吗嗎员員响響问問单單卖賣图圖场場块塊声聲处處备備复復头頭夺奪妈媽宝寶宫宮宾賓岁歲岗崗岭嶺币幣师師带帶帮幫广廣庆慶库庫应應废廢开開异異弃棄张張弥彌弹彈录錄彻徹径徑态態怀懷恶惡惊驚惯慣战戰户戶扑撲执執扩擴扫掃扬揚护護报報担擔拥擁择擇挂掛换換据據摆擺敌敵数數断斷旧舊显顯晓曉暂暫条條杨楊枪槍检檢楼樓欧歐残殘杀殺杂雜齿齒龄齡凯凱凛凜枫楓绫綾辉輝纱紗恋戀灯燈银銀凉涼莲蓮绘繪纯純绝絕绿綠红紅缘緣绀紺"

    private val TRAD: Map<Char, Char> = HashMap<Char, Char>(TRAD_PAIRS.length / 2).apply {
        var i = 0
        while (i + 1 < TRAD_PAIRS.length) {
            put(TRAD_PAIRS[i], TRAD_PAIRS[i + 1])
            i += 2
        }
    }

    /** 常见异体写法归一（如 圣娅 / 圣亚 两种粉丝常用译名） */
    private val VARIANT: Map<Char, Char> = mapOf('娅' to '亚')

    /** 简体 → 繁体 / 日文汉字（仅用于查询与匹配，不用于展示） */
    fun toTrad(s: String): String = buildString(s.length) {
        for (c in s) append(TRAD[c] ?: c)
    }

    /** 归一化：异体字统一 + 简繁统一 + 小写 */
    fun canon(s: String): String {
        val lower = s.lowercase()
        return buildString(lower.length) {
            for (c0 in lower) {
                val c = VARIANT[c0] ?: c0
                append(TRAD[c] ?: c)
            }
        }
    }

    /**
     * 逐级去尾的查询变体（每层含 原文 + 繁体变体）：
     * 百合园圣娅 → [百合园圣娅, 百合園聖婭] → [百合园圣, 百合園聖] → [百合园, 百合園] → …
     */
    fun queryLevels(input: String, maxLevels: Int = 6): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = input
        var level = 0
        while (cur.length >= 2 && level < maxLevels) {
            val set = LinkedHashSet<String>()
            set.add(cur)
            set.add(toTrad(cur))
            out.add(set.toList())
            cur = cur.dropLast(1)
            level++
        }
        return out
    }

    /** 打分：与输入（归一化后）的公共前缀越长越相关；中文翻译命中同样计分 */
    fun score(input: String, suggestion: TagSuggestion): Int {
        val a = canon(input)
        if (a.isEmpty()) return 0
        var best = commonPrefixLen(a, canon(suggestion.tagName))
        if (suggestion.translation.isNotEmpty()) {
            val t = commonPrefixLen(a, canon(suggestion.translation))
            if (t > best) best = t
        }
        return best
    }

    private fun commonPrefixLen(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }
}
