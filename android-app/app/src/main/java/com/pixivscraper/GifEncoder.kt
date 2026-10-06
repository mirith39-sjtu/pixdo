package com.pixivscraper

import java.io.ByteArrayOutputStream

/**
 * 极简 GIF89a 动画编码器（纯 Kotlin、零依赖），用于把 pixiv 动图（ugoira）的帧序列
 * 合成为可直接播放的动图文件（下载原图拿到的只是静态首帧）。
 *
 * 实现要点：
 * - 每帧独立局部调色板：5-5-5 色彩直方图 → 中位切分量化到 ≤256 色
 * - 像素→调色板映射带色彩桶缓存（每个桶只计算一次最近色）
 * - 自写 LZW 编码（每帧重置字典）、逐帧延迟（毫秒 → 10ms 单位）、无限循环
 * - 输入像素为 ARGB IntArray（与 Bitmap.getPixels 对齐）；半透明像素按白底合成
 *
 * 用法（流式，逐帧喂入，避免同时持有全部帧）：
 * ```
 * val enc = GifEncoder(w, h)
 * frames.forEach { enc.addFrame(it.pixels, it.delayMs) }
 * val gif = enc.finish()
 * ```
 */
class GifEncoder(private val width: Int, private val height: Int) {

    private val out = ByteArrayOutputStream(1 shl 20)
    private var finished = false

    // 每帧复用的工作缓冲
    private val hist = IntArray(32768)
    private val mapCache = IntArray(32768)
    private val dictKeys = IntArray(1 shl 13)
    private val dictVals = IntArray(1 shl 13)
    private val block = ByteArray(255)
    private val buckets = IntArray(32)

    init {
        require(width in 1..65535 && height in 1..65535) { "非法尺寸" }
        writeHeader()
    }

    /** 追加一帧（pixels 长度必须 = width*height） */
    fun addFrame(pixels: IntArray, delayMs: Int) {
        require(!finished) { "编码器已结束" }
        require(pixels.size == width * height) { "帧尺寸与画布不一致" }

        val palette = buildPalette(pixels)
        val indices = mapToPalette(pixels, palette)

        // ---- 图形控制扩展：不处置、无透明色、延迟（单位 10ms） ----
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x04)
        writeShort(((delayMs + 5) / 10).coerceIn(2, 65535))
        out.write(0)
        out.write(0)

        // ---- 图像描述符 + 局部调色板（256 色） ----
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        out.write(0x80 or 0x07)
        for (i in 0 until 256) {
            val c = palette[i]
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }

        // ---- LZW 图像数据（最小码长固定 8） ----
        out.write(8)
        writeLzw(indices)
    }

    fun finish(): ByteArray {
        if (!finished) {
            out.write(0x3B)
            finished = true
        }
        return out.toByteArray()
    }

    // ---------------- 文件头 ----------------

    private fun writeHeader() {
        for (ch in "GIF89a") out.write(ch.code)
        writeShort(width); writeShort(height)
        out.write(0x00)   // 无全局调色板（每帧带局部调色板）
        out.write(0)
        out.write(0)
        // NETSCAPE2.0 无限循环
        out.write(0x21); out.write(0xFF); out.write(11)
        for (ch in "NETSCAPE2.0") out.write(ch.code)
        out.write(3); out.write(1); writeShort(0); out.write(0)
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    // ---------------- 像素 → 5-5-5 色彩桶 ----------------

    private fun binOf(argb: Int): Int {
        val a = argb ushr 24
        var r = (argb shr 16) and 0xFF
        var g = (argb shr 8) and 0xFF
        var b = argb and 0xFF
        if (a < 255) {
            // 半透明 → 白底合成（pixiv 动图通常不透明，这里防御性处理）
            val inv = 255 - a
            r = (r * a + 255 * inv) / 255
            g = (g * a + 255 * inv) / 255
            b = (b * a + 255 * inv) / 255
        }
        return ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
    }

    // ---------------- 调色板（中位切分量化） ----------------

    private fun buildPalette(pixels: IntArray): IntArray {
        hist.fill(0)
        for (p in pixels) hist[binOf(p)]++

        // 收集非空色彩桶 → 颜色列表
        var n = 0
        var cols = IntArray(1024)
        var cnts = IntArray(1024)
        for (bin in 0 until 32768) {
            val c = hist[bin]
            if (c == 0) continue
            if (n == cols.size) {
                cols = cols.copyOf(n * 2)
                cnts = cnts.copyOf(n * 2)
            }
            val r = (((bin shr 10) and 31) shl 3) or 4
            val g = (((bin shr 5) and 31) shl 3) or 4
            val b = ((bin and 31) shl 3) or 4
            cols[n] = (r shl 16) or (g shl 8) or b
            cnts[n] = c
            n++
        }
        if (n == 0) {
            val palette = IntArray(256)
            return palette
        }

        // 箱子：[lo, hi, rmin, rmax, gmin, gmax, bmin, bmax, count]
        val boxes = ArrayList<IntArray>(256)
        boxes.add(boundsOf(cols, cnts, 0, n))

        while (boxes.size < 256) {
            var bi = -1
            var bestCount = -1
            for (i in boxes.indices) {
                val b = boxes[i]
                if (b[8] <= 0 || b[1] - b[0] < 2) continue
                if (maxOf(b[3] - b[2], b[5] - b[4], b[7] - b[6]) <= 0) continue
                if (b[8] > bestCount) {
                    bestCount = b[8]
                    bi = i
                }
            }
            if (bi < 0) break

            val b = boxes[bi]
            val rr = b[3] - b[2]
            val gr = b[5] - b[4]
            val br = b[7] - b[6]
            val ch = when {
                rr >= gr && rr >= br -> 0
                gr >= br -> 1
                else -> 2
            }
            sortSegment(cols, cnts, b[0], b[1], ch)

            var acc = 0
            var k = b[0]
            val half = b[8] / 2
            while (k < b[1] - 1 && acc < half) {
                acc += cnts[k]
                k++
            }
            if (k <= b[0] || k >= b[1]) {
                // 该箱子无法再分（同色），标记跳过
                b[8] = 0
                continue
            }
            boxes[bi] = boundsOf(cols, cnts, b[0], k)
            boxes.add(boundsOf(cols, cnts, k, b[1]))
        }

        // 每个箱子的加权平均色 → 调色板
        val palette = IntArray(256)
        var pi = 0
        for (b in boxes) {
            if (pi >= 256) break
            var sr = 0L; var sg = 0L; var sb = 0L; var sc = 0L
            for (i in b[0] until b[1]) {
                val c = cols[i]
                val k = cnts[i].toLong()
                sr += ((c shr 16) and 255) * k
                sg += ((c shr 8) and 255) * k
                sb += (c and 255) * k
                sc += k
            }
            if (sc == 0L) continue
            palette[pi++] = (((sr / sc).toInt()) shl 16) or
                (((sg / sc).toInt()) shl 8) or
                ((sb / sc).toInt())
        }
        return palette
    }

    private fun boundsOf(cols: IntArray, cnts: IntArray, lo: Int, hi: Int): IntArray {
        var rmin = 255; var rmax = 0
        var gmin = 255; var gmax = 0
        var bmin = 255; var bmax = 0
        var count = 0
        for (i in lo until hi) {
            val c = cols[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            if (r < rmin) rmin = r
            if (r > rmax) rmax = r
            if (g < gmin) gmin = g
            if (g > gmax) gmax = g
            if (b < bmin) bmin = b
            if (b > bmax) bmax = b
            count += cnts[i]
        }
        return intArrayOf(lo, hi, rmin, rmax, gmin, gmax, bmin, bmax, count)
    }

    private fun channelOf(c: Int, ch: Int): Int = when (ch) {
        0 -> (c shr 16) and 255
        1 -> (c shr 8) and 255
        else -> c and 255
    }

    /** 段内三向快排（迭代版），按指定通道排序 */
    private fun sortSegment(cols: IntArray, cnts: IntArray, lo0: Int, hi0: Int, ch: Int) {
        if (hi0 - lo0 < 2) return
        val stack = java.util.ArrayDeque<Int>()
        stack.addLast(lo0); stack.addLast(hi0)
        while (stack.isNotEmpty()) {
            val hi = stack.removeLast()
            val lo = stack.removeLast()
            val len = hi - lo
            if (len < 12) {
                for (i in lo + 1 until hi) {
                    val tc = cols[i]; val tk = cnts[i]
                    var j = i - 1
                    while (j >= lo && channelOf(cols[j], ch) > channelOf(tc, ch)) {
                        cols[j + 1] = cols[j]; cnts[j + 1] = cnts[j]
                        j--
                    }
                    cols[j + 1] = tc; cnts[j + 1] = tk
                }
                continue
            }
            val pivot = channelOf(cols[(lo + hi) ushr 1], ch)
            var i = lo
            var j = hi - 1
            while (i <= j) {
                while (channelOf(cols[i], ch) < pivot) i++
                while (channelOf(cols[j], ch) > pivot) j--
                if (i <= j) {
                    val tc = cols[i]; cols[i] = cols[j]; cols[j] = tc
                    val tk = cnts[i]; cnts[i] = cnts[j]; cnts[j] = tk
                    i++; j--
                }
            }
            if (lo < j) { stack.addLast(lo); stack.addLast(j + 1) }
            if (i < hi - 1) { stack.addLast(i); stack.addLast(hi) }
        }
    }

    /** 像素 → 调色板索引（色彩桶缓存 + 首次最近色搜索） */
    private fun mapToPalette(pixels: IntArray, palette: IntArray): ByteArray {
        mapCache.fill(-1)
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val bin = binOf(pixels[i])
            var idx = mapCache[bin]
            if (idx < 0) {
                val r = (((bin shr 10) and 31) shl 3) or 4
                val g = (((bin shr 5) and 31) shl 3) or 4
                val b = ((bin and 31) shl 3) or 4
                idx = nearest(palette, r, g, b)
                mapCache[bin] = idx
            }
            indices[i] = idx.toByte()
        }
        return indices
    }

    private fun nearest(palette: IntArray, r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in 0 until 256) {
            val c = palette[i]
            val dr = ((c shr 16) and 255) - r
            val dg = ((c shr 8) and 255) - g
            val db = (c and 255) - b
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) {
                bestD = d
                best = i
                if (d == 0) break
            }
        }
        return best
    }

    // ---------------- LZW（GIF 变体，LSB 位序，≤255 字节子块） ----------------

    private fun writeLzw(data: ByteArray) {
        var bitBuf = 0
        var bitCount = 0
        var blockLen = 0

        fun flushBlock() {
            if (blockLen > 0) {
                out.write(blockLen)
                out.write(block, 0, blockLen)
                blockLen = 0
            }
        }

        fun emit(byte: Int) {
            block[blockLen++] = byte.toByte()
            if (blockLen == 255) flushBlock()
        }

        val clearCode = 256
        val eoiCode = 257
        var codeSize = 9
        var nextCode = 258
        java.util.Arrays.fill(dictKeys, 0)

        fun writeCode(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                emit(bitBuf and 0xFF)
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        fun findSlot(key: Int): Int {
            var i = (key * 0x9E3779B1.toInt()) ushr 19
            while (true) {
                val k = dictKeys[i]
                if (k == 0 || k == key + 1) return i
                i = (i + 1) and 0x1FFF
            }
        }

        writeCode(clearCode)
        var prefix = data[0].toInt() and 0xFF
        var i = 1
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            val slot = findSlot(key)
            if (dictKeys[slot] == key + 1) {
                prefix = dictVals[slot]
            } else {
                writeCode(prefix)
                if (nextCode <= 4095) {
                    dictKeys[slot] = key + 1
                    dictVals[slot] = nextCode
                    // 注意：与解码器对齐的时机是「写入本条码之后、加入新条目之前」判断，
                    // 且用的是加入前的值（经典 LZW 差一位问题）
                    if (codeSize < 12 && nextCode > (1 shl codeSize) - 1) codeSize++
                    nextCode++
                } else {
                    writeCode(clearCode)
                    java.util.Arrays.fill(dictKeys, 0)
                    nextCode = 258
                    codeSize = 9
                }
                prefix = c
            }
            i++
        }
        writeCode(prefix)
        writeCode(eoiCode)
        if (bitCount > 0) emit(bitBuf and 0xFF)
        flushBlock()
        out.write(0)
    }
}
