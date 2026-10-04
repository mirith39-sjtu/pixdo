package com.pixivscraper

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * pixiv Web API 客户端。
 * Cookie 直接取自 WebView 的 CookieManager（登录页保存的会话），
 * 游客状态下 R18 不会出现在搜索结果里。
 */
object PixivApi {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val BASE = "https://www.pixiv.net"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder().header("User-Agent", DESKTOP_UA)
                val host = req.url.host
                val cookie = CookieManager.getInstance().getCookie(BASE) ?: ""
                if (host.endsWith("pixiv.net")) {
                    b.header("Referer", "$BASE/")
                    b.header("Accept-Language", "ja-JP,ja;q=0.9,en;q=0.8")
                    if (cookie.isNotEmpty()) b.header("Cookie", cookie)
                } else if (host.endsWith("pximg.net")) {
                    // 图片 CDN：带上 PHPSESSID（与桌面版一致）
                    b.header("Referer", "$BASE/")
                    extractCookie(cookie, "PHPSESSID")?.let { b.header("Cookie", "PHPSESSID=$it") }
                }
                chain.proceed(b.build())
            }
            .build()
    }

    /** 标签联想专用客户端：纯游客、不带 cookie（过期会话会导致联想接口返回 400） */
    private val suggestClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "$BASE/")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    private fun extractCookie(cookies: String, name: String): String? {
        for (part in cookies.split(";")) {
            val kv = part.trim()
            val eq = kv.indexOf('=')
            if (eq > 0 && kv.substring(0, eq) == name) return kv.substring(eq + 1)
        }
        return null
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun fetchToString(url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                Pair(resp.code, resp.body?.string())
            }
        } catch (e: Exception) {
            Pair(-1, null)
        }
    }

    /** 带 429 重试（等 30 秒再来一次，与桌面版一致） */
    private suspend fun fetchWithRetry(url: String): Pair<Int, String?> {
        var r = fetchToString(url)
        if (r.first == 429) {
            delay(30_000)
            r = fetchToString(url)
        }
        return r
    }

    /** 真实登录校验：游客 401，已登录 200（不能用 PHPSESSID 是否存在来判断） */
    suspend fun isLoggedIn(): Boolean {
        val (code, text) = fetchToString("$BASE/ajax/follow_latest/illust?p=1&mode=all&lang=ja")
        if (code == 200 && text != null) {
            return try {
                !JSONObject(text).optBoolean("error")
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    /** 实时标签联想（pixiv 官方接口，游客可用；中文→日文 tag 由 tag_translation 匹配） */
    suspend fun suggestTags(keyword: String): List<TagSuggestion> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val url = "$BASE/rpc/cps.php?keyword=${enc(kw)}&lang=zh"
        val (code, text) = withContext(Dispatchers.IO) {
            try {
                suggestClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    Pair(resp.code, resp.body?.string())
                }
            } catch (e: Exception) {
                Pair(-1, null)
            }
        }
        if (code != 200 || text == null) return emptyList()
        return try {
            val arr = JSONObject(text).optJSONArray("candidates") ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<TagSuggestion>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val name = c.optString("tag_name").trim()
                if (name.isEmpty() || !seen.add(name)) continue
                val trans = if (c.isNull("tag_translation")) "" else c.optString("tag_translation").trim()
                out.add(
                    TagSuggestion(
                        tagName = name,
                        translation = trans,
                        accessCount = c.optString("access_count").toLongOrNull() ?: 0L,
                        type = c.optString("type"),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 按标签搜索。mode: all / safe / r18 */
    suspend fun search(tag: String, order: String, page: Int, mode: String): List<WorkBrief> {
        val encTag = enc(tag)
        val url = "$BASE/ajax/search/illustrations/$encTag?word=$encTag&order=$order&mode=$mode&p=$page&s_mode=s_tag&type=illust&lang=ja"
        val (code, text) = fetchWithRetry(url)
        if (code != 200 || text == null) return emptyList()
        return try {
            val json = JSONObject(text)
            if (json.optBoolean("error")) return emptyList()
            val body = json.optJSONObject("body") ?: return emptyList()
            var arr: JSONArray? = body.optJSONObject("illustManga")?.optJSONArray("data")
            if (arr == null || arr.length() == 0) arr = body.optJSONObject("illust")?.optJSONArray("data")
            if (arr == null || arr.length() == 0) arr = body.optJSONArray("data")
            if (arr == null) return emptyList()
            val out = ArrayList<WorkBrief>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                out.add(
                    WorkBrief(
                        id = id,
                        title = item.optString("title"),
                        userName = item.optString("userName"),
                        pageCount = item.optInt("pageCount", 1),
                        xRestrict = item.optInt("xRestrict"),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 作品详情（含多页图片 URL） */
    suspend fun detail(id: String): WorkDetail? {
        val (code, text) = fetchWithRetry("$BASE/ajax/illust/$id?lang=ja")
        if (code != 200 || text == null) return null
        val b: JSONObject = try {
            val json = JSONObject(text)
            if (json.optBoolean("error")) return null
            json.optJSONObject("body") ?: return null
        } catch (e: Exception) {
            return null
        }

        val urls = ArrayList<String>()
        val urlsObj = b.optJSONObject("urls")
        if (urlsObj != null) {
            val u1 = urlsObj.optString("original")
            val u2 = urlsObj.optString("regular")
            if (u1.isNotEmpty()) {
                urls.add(u1)
            } else if (u2.isNotEmpty()) {
                urls.add(u2)
            }
        }

        val pageCount = b.optInt("pageCount", 1)
        if (pageCount > 1) {
            val (pc, pt) = fetchWithRetry("$BASE/ajax/illust/$id/pages?lang=ja")
            if (pc == 200 && pt != null) {
                try {
                    val arr = JSONObject(pt).optJSONArray("body")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val u = arr.optJSONObject(i)?.optJSONObject("urls") ?: continue
                            val u1 = u.optString("original")
                            val u2 = u.optString("regular")
                            if (u1.isNotEmpty()) {
                                urls.add(u1)
                            } else if (u2.isNotEmpty()) {
                                urls.add(u2)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val likeCount = b.optLong("likeCount").let { if (it > 0) it else b.optLong("bookmarkCount") }

        return WorkDetail(
            id = id,
            title = b.optString("title").ifEmpty { b.optString("illustTitle") },
            author = b.optString("userName").ifEmpty { b.optString("userAccount") },
            authorId = b.optString("userId"),
            likeCount = likeCount,
            viewCount = b.optLong("viewCount"),
            bookmarkCount = b.optLong("bookmarkCount"),
            pageCount = pageCount,
            isR18 = b.optInt("xRestrict") > 0,
            tags = parseTags(b.opt("tags")),
            imageUrls = urls,
            url = "$BASE/artworks/$id",
        )
    }

    private fun parseTags(raw: Any?): List<String> {
        val out = ArrayList<String>()
        when (raw) {
            is JSONObject -> {
                val arr = raw.optJSONArray("tags") ?: return out
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optString("tag")
                    if (t.isNotEmpty()) out.add(t)
                }
            }
            is JSONArray -> {
                for (i in 0 until raw.length()) {
                    when (val item = raw.opt(i)) {
                        is JSONObject -> {
                            val t = item.optString("tag")
                            if (t.isNotEmpty()) out.add(t)
                        }
                        is String -> out.add(item)
                    }
                }
            }
        }
        return out
    }

    /** 下载图片，失败返回 null */
    suspend fun downloadImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.code == 200) {
                    resp.body?.bytes()?.takeIf { it.size > 1000 }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
