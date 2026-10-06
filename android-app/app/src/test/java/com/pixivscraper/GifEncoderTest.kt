package com.pixivscraper

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * GifEncoder 的 JVM 单元测试：
 * - 合成帧：验证编码流程可跑通、输出文件可生成
 * - 真实动图帧（可选）：设置环境变量 UGOIRA_FRAMES_DIR 指向 pixiv 动图 zip
 *   解压出的帧目录，会生成 output 供电脑端用 Pillow 等工具校验
 */
class GifEncoderTest {

    @Test
    fun syntheticFramesEncode() {
        val w = 96
        val h = 72
        val enc = GifEncoder(w, h)
        for (f in 0 until 12) {
            val px = IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                val moving = (x + f * 6) % w
                if (moving in 10..30 && y in 10..30) {
                    0xFFFF3B30.toInt()
                } else {
                    0xFF000000.toInt() or ((x * 2) shl 16) or ((y * 3) shl 8) or 0x40
                }
            }
            enc.addFrame(px, 80)
        }
        val bytes = enc.finish()
        val out = File("build/test-output/synthetic.gif")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("synthetic gif = ${bytes.size} bytes -> ${out.absolutePath}")
        assertTrue("输出过小: ${bytes.size}", bytes.size > 200)
    }

    @Test
    fun realUgoiraFramesEncode() {
        val dir = File(System.getenv("UGOIRA_FRAMES_DIR") ?: "")
        if (!dir.isDirectory) {
            println("未设置 UGOIRA_FRAMES_DIR，跳过真实帧测试")
            return
        }
        val files = dir.listFiles { f -> f.isFile && f.extension == "bin" }
            ?.sortedBy { it.name } ?: return
        if (files.isEmpty()) return

        val t0 = System.currentTimeMillis()
        var enc: GifEncoder? = null
        var count = 0
        var w = 0
        var h = 0
        for (f in files) {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { ins ->
                val fw = readIntLE(ins)
                val fh = readIntLE(ins)
                val px = IntArray(fw * fh)
                val buf = ByteArray(fw * fh * 4)
                ins.readFully(buf)
                for (i in px.indices) {
                    val o = i * 4
                    px[i] = ((buf[o + 3].toInt() and 0xFF) shl 24) or
                        ((buf[o + 2].toInt() and 0xFF) shl 16) or
                        ((buf[o + 1].toInt() and 0xFF) shl 8) or
                        (buf[o].toInt() and 0xFF)
                }
                val e = enc ?: GifEncoder(fw, fh).also {
                    w = fw
                    h = fh
                    enc = it
                }
                e.addFrame(px, 35)
                count++
            }
        }
        val bytes = enc?.finish() ?: return
        val out = File("build/test-output/real-ugoira.gif")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        println("real gif = ${bytes.size / 1024} KB, $count frames, ${w}x$h, 耗时 ${sec}s -> ${out.absolutePath}")
        assertTrue("输出过小: ${bytes.size}", bytes.size > 1000)
    }

    private fun readIntLE(ins: DataInputStream): Int {
        val b0 = ins.read()
        val b1 = ins.read()
        val b2 = ins.read()
        val b3 = ins.read()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or
            ((b3 and 0xFF) shl 24)
    }
}
