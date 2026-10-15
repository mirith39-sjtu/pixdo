package com.pixivscraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkPostProcessTest {

    // 两两汉明距离足够大的指纹（避免无意间构成「近似重复」）
    private val h1 = 0x0123456789ABCDEFL
    private val h2 = 0x76543210FEDCBA98L
    private val h3 = 0x5555555555555555L
    private val h4 = 0x2AAAAAAAAAAAAAAAL

    private fun sig(
        md5: String?,
        w: Int,
        h: Int,
        hash: Long?,
        blur: Double? = 60.0,
        r: Double = 100.0,
        g: Double = 100.0,
        b: Double = 100.0,
    ) = WorkPostProcess.Sig(md5, w, h, hash, r, g, b, blur)

    /** 横向条纹图案（用于 dHash） */
    private fun stripes(w: Int = 64, h: Int = 64): IntArray {
        val a = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                a[y * w + x] = if ((x / 8) % 2 == 0) 240 else 10
            }
        }
        return a
    }

    /** 平滑渐变（近似「模糊」的图） */
    private fun smooth(w: Int = 64, h: Int = 64): IntArray {
        val a = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                a[y * w + x] = (x * 255) / (w - 1)
            }
        }
        return a
    }

    @Test
    fun identicalMd5IsDropped() {
        val sigs = listOf(sig("aaa", 600, 900, h1), sig("aaa", 600, 900, h1))
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = false)
        assertEquals(setOf(1), drop.keys)
        assertTrue(drop[1]!!.contains("完全重复"))
    }

    @Test
    fun nearDuplicateIsDropped() {
        val sigs = listOf(sig("aa", 600, 900, h1), sig("bb", 600, 900, h1 xor 3L))
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = false)
        assertEquals(setOf(1), drop.keys)
        assertTrue(drop[1]!!.contains("近似重复"))
    }

    @Test
    fun colorVariantIsKept() {
        val sigs = listOf(
            sig("aa", 600, 900, h1, r = 40.0, g = 40.0, b = 40.0),
            sig("bb", 600, 900, h1 xor 1L, r = 220.0, g = 40.0, b = 40.0),
        )
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = false)
        assertTrue(drop.isEmpty())
    }

    @Test
    fun differentSizeIsNotNearDuplicate() {
        val sigs = listOf(sig("aa", 600, 900, h1), sig("bb", 900, 600, h1))
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = false)
        assertTrue(drop.isEmpty())
    }

    @Test
    fun landscapeLastIsTrailer() {
        val sigs = listOf(
            sig("a", 600, 900, h1),
            sig("b", 600, 900, h2),
            sig("c", 1200, 630, h3),
        )
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = true)
        assertEquals(setOf(2), drop.keys)
        assertTrue(drop[2]!!.contains("预告图"))
    }

    @Test
    fun portraitLastIsKept() {
        val sigs = listOf(sig("a", 600, 900, h1), sig("b", 600, 900, h2))
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = true)
        assertTrue(drop.isEmpty())
    }

    @Test
    fun bodyWithLandscapeKeepsLast() {
        val sigs = listOf(
            sig("a", 600, 900, h1),
            sig("b", 1200, 800, h2),
            sig("c", 1200, 630, h3),
        )
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = true)
        assertTrue(drop.isEmpty())
    }

    @Test
    fun mildLandscapeBlurryLastIsTrailer() {
        val sigs = listOf(
            sig("a", 600, 900, h1, blur = 80.0),
            sig("b", 600, 900, h2, blur = 75.0),
            sig("c", 630, 600, h3, blur = 9.0),
        )
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = true)
        assertEquals(setOf(2), drop.keys)
    }

    @Test
    fun togglesOffMeansNoDrop() {
        val sigs = listOf(
            sig("a", 600, 900, h1),
            sig("a", 600, 900, h1),
            sig("c", 1200, 630, h2),
        )
        val drop = WorkPostProcess.plan(sigs, dedupe = false, near = false, trailer = false)
        assertTrue(drop.isEmpty())
    }

    @Test
    fun singlePageNeverDropped() {
        val drop = WorkPostProcess.plan(
            listOf(sig("a", 600, 900, h1)), dedupe = true, near = true, trailer = true)
        assertTrue(drop.isEmpty())
    }

    @Test
    fun unknownSignatureIsNeverDropped() {
        val sigs = listOf(WorkPostProcess.Sig.UNKNOWN, WorkPostProcess.Sig.UNKNOWN)
        val drop = WorkPostProcess.plan(sigs, dedupe = true, near = true, trailer = true)
        assertTrue(drop.isEmpty())
    }

    @Test
    fun dHashIsStableAndDiscriminative() {
        val a1 = WorkPostProcess.dHashFromGray(stripes(), 64, 64)
        val a2 = WorkPostProcess.dHashFromGray(stripes(), 64, 64)
        val b = WorkPostProcess.dHashFromGray(smooth(), 64, 64)
        assertNotNull(a1)
        assertEquals(a1, a2)
        assertNotNull(b)
        assertTrue(WorkPostProcess.hamming(a1!!, b!!) > WorkPostProcess.NEAR_HASH_MAX)
    }

    @Test
    fun blurMetricSeparatesSharpAndSmooth() {
        val sharp = WorkPostProcess.blurFromGray(stripes(), 64, 64)
        val soft = WorkPostProcess.blurFromGray(smooth(), 64, 64)
        assertNotNull(sharp)
        assertNotNull(soft)
        assertTrue(sharp!! > soft!!)
    }

    @Test
    fun trailerRuleNeedsPortraitBody() {
        val last = sig("c", 1200, 630, h3)
        assertNull(WorkPostProcess.looksLikeTrailer(last, emptyList()))
        assertNotNull(WorkPostProcess.looksLikeTrailer(last, listOf(sig("a", 600, 900, h1))))
        // 正文全是横向：不再当作预告图
        assertNull(
            WorkPostProcess.looksLikeTrailer(
                last, listOf(sig("a", 1600, 900, h1), sig("b", 1600, 1000, h2)))
        )
    }
}
