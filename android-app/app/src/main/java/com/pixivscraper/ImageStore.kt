package com.pixivscraper

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * 图片保存到系统相册 Pictures/PixivScraper/<标签>/<safe|r18>/。
 * - Android 10+ : 通过 MediaStore 保存（不需要存储权限），查重索引从 MediaStore 批量查询
 * - Android 9-  : 直接写公共 Pictures 目录（需要 WRITE_EXTERNAL_STORAGE 权限）
 *
 * key = 记录在查重表里的文件标识：
 *   Android 10+ : "Pictures/PixivScraper/<标签>/<safe|r18>/<文件名>"
 *   Android 9-  : 文件的绝对路径
 */
class ImageStore(private val context: Context) {

    companion object {
        private const val ROOT_NAME = "PixivScraper"
    }

    /** 由相对目录 + 文件名计算查重 key（不实际创建文件） */
    fun keyFor(relFolder: String, fileName: String): String =
        if (Build.VERSION.SDK_INT >= 29) {
            "Pictures/$ROOT_NAME/$relFolder/$fileName"
        } else {
            File(legacyRoot(), "$relFolder/$fileName").absolutePath
        }

    /** 保存一张图片，成功返回 key，失败返回 null */
    fun save(relFolder: String, fileName: String, mime: String, bytes: ByteArray): String? =
        try {
            if (Build.VERSION.SDK_INT >= 29) saveModern(relFolder, fileName, mime, bytes)
            else saveLegacy(relFolder, fileName, bytes)
        } catch (e: Exception) {
            null
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveModern(relFolder: String, fileName: String, mime: String, bytes: ByteArray): String? {
        val resolver = context.contentResolver
        val relPath = "Pictures/$ROOT_NAME/$relFolder"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val os = resolver.openOutputStream(uri) ?: return null
        os.use { it.write(bytes) }
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)

        // 重名时系统可能自动改名，回读实际文件名
        var actual = fileName
        resolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) actual = c.getString(0) ?: fileName
        }
        return "$relPath/$actual"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(relFolder: String, fileName: String, bytes: ByteArray): String? {
        val dir = File(legacyRoot(), relFolder)
        if (!dir.mkdirs() && !dir.isDirectory) return null
        var f = File(dir, fileName)
        if (f.exists()) {
            val base = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "")
            var n = 1
            while (f.exists()) {
                f = File(dir, "$base ($n).$ext")
                n++
            }
        }
        f.outputStream().use { it.write(bytes) }
        return f.absolutePath
    }

    @Suppress("DEPRECATION")
    private fun legacyRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ROOT_NAME)

    /** 汇总当前相册里本应用保存过的所有文件 key（用于对账/查重） */
    fun buildIndex(): Set<String> = try {
        if (Build.VERSION.SDK_INT >= 29) queryModern() else scanLegacy()
    } catch (e: Exception) {
        emptySet()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryModern(): Set<String> {
        val out = HashSet<String>()
        val projection = arrayOf(
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf("Pictures/$ROOT_NAME/%"),
            null
        )?.use { c ->
            val relIdx = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val rel = c.getString(relIdx) ?: continue
                val name = c.getString(nameIdx) ?: continue
                out.add(rel.trimEnd('/') + "/" + name)
            }
        }
        return out
    }

    private fun scanLegacy(): Set<String> {
        val out = HashSet<String>()
        val root = legacyRoot()
        if (!root.isDirectory) return out
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) stack.add(f)
                else if (f.length() > 0) out.add(f.absolutePath)
            }
        }
        return out
    }
}
