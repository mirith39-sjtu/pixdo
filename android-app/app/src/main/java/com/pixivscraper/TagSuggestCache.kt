package com.pixivscraper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 标签联想本地缓存（与 PC 端同构）：
 * - queries: 关键词 → 候选列表（命中秒回，不重复请求）
 * - pool:    所有见过的标签（断网/在线无结果时做子串匹配兜底）
 */
class TagSuggestCache(context: Context) {

    private val file = File(context.filesDir, "tag_suggest_cache.json")
    private val lock = Any()

    private fun load(): JSONObject = synchronized(lock) {
        val root: JSONObject = try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            JSONObject()
        }
        if (root.optJSONObject("queries") == null) root.put("queries", JSONObject())
        if (root.optJSONObject("pool") == null) root.put("pool", JSONObject())
        root
    }

    /** 关键词的缓存结果；没有则返回 null */
    fun getQuery(keyword: String): List<TagSuggestion>? {
        val arr = load().optJSONObject("queries")?.optJSONArray(keyword) ?: return null
        if (arr.length() == 0) return null
        return parseList(arr)
    }

    /** 记录一次在线查询结果（同时并入标签池） */
    fun saveQuery(keyword: String, items: List<TagSuggestion>) {
        val root = load()
        val queries = root.optJSONObject("queries") ?: JSONObject().also { root.put("queries", it) }
        queries.put(keyword, toArray(items))
        val pool = root.optJSONObject("pool") ?: JSONObject().also { root.put("pool", it) }
        items.forEach { s ->
            pool.put(s.tagName, JSONObject().apply {
                put("tag_name", s.tagName)
                put("translation", s.translation)
                put("access_count", s.accessCount)
                put("type", s.type)
            })
        }
        trim(queries, pool)
        store(root)
    }

    /** 离线兜底：在标签池里做子串匹配（名称/翻译），简繁与异体字归一后匹配；前缀优先、热度次之 */
    fun searchPool(keyword: String, limit: Int = 10): List<TagSuggestion> {
        val kw = keyword.lowercase()
        if (kw.isEmpty()) return emptyList()
        val kwc = TagSuggester.canon(kw)
        val pool = load().optJSONObject("pool") ?: return emptyList()
        val scored = ArrayList<Triple<Int, Long, TagSuggestion>>()
        val keys = pool.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val o = pool.optJSONObject(name) ?: continue
            val trans = o.optString("translation")
            val nl = name.lowercase()
            val tl = trans.lowercase()
            val nlc = TagSuggester.canon(nl)
            val tlc = if (tl.isNotEmpty()) TagSuggester.canon(tl) else ""
            val hit = kw in nl || (tl.isNotEmpty() && kw in tl) ||
                (kwc.isNotEmpty() && (kwc in nlc || (tlc.isNotEmpty() && kwc in tlc)))
            if (hit) {
                val prefix = nl.startsWith(kw) || (tl.isNotEmpty() && tl.startsWith(kw)) ||
                    (kwc.isNotEmpty() && (nlc.startsWith(kwc) || (tlc.isNotEmpty() && tlc.startsWith(kwc))))
                scored.add(
                    Triple(if (prefix) 0 else 1, -o.optLong("access_count"), objToSuggestion(name, o))
                )
            }
        }
        scored.sortWith(compareBy({ it.first }, { it.second }))
        return scored.take(limit).map { it.third }
    }

    private fun parseList(arr: JSONArray): List<TagSuggestion> {
        val out = ArrayList<TagSuggestion>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(objToSuggestion(o.optString("tag_name"), o))
        }
        return out
    }

    private fun objToSuggestion(name: String, o: JSONObject): TagSuggestion =
        TagSuggestion(
            tagName = name,
            translation = o.optString("translation"),
            accessCount = o.optLong("access_count"),
            type = o.optString("type"),
        )

    private fun toArray(items: List<TagSuggestion>): JSONArray {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("tag_name", s.tagName)
                put("translation", s.translation)
                put("access_count", s.accessCount)
                put("type", s.type)
            })
        }
        return arr
    }

    private fun store(root: JSONObject) {
        synchronized(lock) {
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(file)) {
                    file.writeText(root.toString())
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 控制规模：queries 最多 ~1500 条，pool 保留热度最高的 15000 个 */
    private fun trim(queries: JSONObject, pool: JSONObject) {
        try {
            if (queries.length() > 1500) {
                val keys = queries.keys()
                val del = ArrayList<String>()
                while (keys.hasNext() && del.size < 300) del.add(keys.next())
                del.forEach { queries.remove(it) }
            }
            if (pool.length() > 20000) {
                val list = ArrayList<Pair<Long, String>>()
                val keys = pool.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    list.add(Pair(-(pool.optJSONObject(k)?.optLong("access_count") ?: 0L), k))
                }
                list.sortBy { it.first }
                list.drop(15000).forEach { pool.remove(it.second) }
            }
        } catch (_: Exception) {
        }
    }
}
