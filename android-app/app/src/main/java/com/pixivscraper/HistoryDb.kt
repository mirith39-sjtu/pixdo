package com.pixivscraper

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DB_NAME = "pixiv_history.db"

private fun nowStamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

/**
 * 查重记录表（与桌面版 pixiv_history.db 同构）。
 * 状态: downloaded / missing / filtered
 * - 手动删除图片后：运行开始时对账发现文件缺失 → missing → 重新补下载
 */
class HistoryDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, 1) {

    data class WorkRecord(
        val id: String,
        var title: String,
        var author: String,
        var authorId: String,
        var likeCount: Long,
        var isR18: Boolean,
        var pageCount: Int,
        var status: String,
        var reason: String,
        var folder: String,
        var files: List<String>,
        var createdAt: String,
        var updatedAt: String,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE works (
                illust_id  TEXT PRIMARY KEY,
                title      TEXT,
                author     TEXT,
                author_id  TEXT,
                like_count INTEGER,
                is_r18     INTEGER,
                page_count INTEGER,
                tags       TEXT,
                url        TEXT,
                status     TEXT,
                reason     TEXT,
                folder     TEXT,
                files      TEXT,
                created_at TEXT,
                updated_at TEXT)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 版本 1：暂无升级逻辑
    }

    fun loadAll(): Map<String, WorkRecord> {
        val out = LinkedHashMap<String, WorkRecord>()
        readableDatabase.rawQuery(SELECT_ALL, null).use { c ->
            while (c.moveToNext()) {
                val rec = rowToRecord(c)
                out[rec.id] = rec
            }
        }
        return out
    }

    fun setStatus(id: String, status: String) {
        val v = ContentValues().apply {
            put("status", status)
            put("updated_at", nowStamp())
        }
        writableDatabase.update("works", v, "illust_id=?", arrayOf(id))
    }

    fun upsertDownloaded(d: WorkDetail, folder: String, files: List<String>) {
        val existing = getRecord(d.id)
        val expected = d.imageUrls.size
        val status = if (files.isNotEmpty() && files.size >= expected) "downloaded" else "missing"
        val v = ContentValues().apply {
            put("illust_id", d.id)
            put("title", d.title)
            put("author", d.author)
            put("author_id", d.authorId)
            put("like_count", d.likeCount)
            put("is_r18", if (d.isR18) 1 else 0)
            put("page_count", if (expected > 0) expected else d.pageCount)
            put("tags", JSONArray(d.tags).toString())
            put("url", d.url)
            put("status", status)
            put("reason", "")
            put("folder", folder)
            put("files", JSONArray(files).toString())
            put("created_at", existing?.createdAt ?: nowStamp())
            put("updated_at", nowStamp())
        }
        writableDatabase.insertWithOnConflict("works", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertFiltered(d: WorkDetail, reason: String) {
        val existing = getRecord(d.id)
        val v = ContentValues().apply {
            put("title", d.title)
            put("author", d.author)
            put("author_id", d.authorId)
            put("like_count", d.likeCount)
            put("is_r18", if (d.isR18) 1 else 0)
            put("page_count", d.pageCount)
            put("tags", JSONArray(d.tags).toString())
            put("url", d.url)
            put("status", "filtered")
            put("reason", reason)
            put("updated_at", nowStamp())
        }
        if (existing == null) {
            v.put("illust_id", d.id)
            v.put("folder", "")
            v.put("files", "[]")
            v.put("created_at", nowStamp())
            writableDatabase.insertWithOnConflict("works", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            // 已有记录（可能是已下载）: 保留 files/folder, 只更新状态字段
            writableDatabase.update("works", v, "illust_id=?", arrayOf(d.id))
        }
    }

    private fun getRecord(id: String): WorkRecord? {
        readableDatabase.rawQuery("$SELECT_ALL WHERE illust_id=? LIMIT 1", arrayOf(id)).use { c ->
            if (c.moveToFirst()) return rowToRecord(c)
        }
        return null
    }

    private fun rowToRecord(c: Cursor): WorkRecord = WorkRecord(
        id = c.getString(0),
        title = c.getString(1) ?: "",
        author = c.getString(2) ?: "",
        authorId = c.getString(3) ?: "",
        likeCount = c.getLong(4),
        isR18 = c.getInt(5) != 0,
        pageCount = c.getInt(6),
        status = c.getString(7) ?: "",
        reason = c.getString(8) ?: "",
        folder = c.getString(9) ?: "",
        files = parseFiles(c.getString(10)),
        createdAt = c.getString(11) ?: "",
        updatedAt = c.getString(12) ?: "",
    )

    private fun parseFiles(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            ArrayList<String>(arr.length()).apply {
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val SELECT_ALL =
            "SELECT illust_id,title,author,author_id,like_count,is_r18,page_count," +
                "status,reason,folder,files,created_at,updated_at FROM works"

        /** 清空查重记录（删除数据库文件） */
        fun clear(context: Context): String {
            val ok = context.deleteDatabase(DB_NAME)
            return if (ok) "查重记录已清空" else "查重记录本来就是空的"
        }
    }
}
