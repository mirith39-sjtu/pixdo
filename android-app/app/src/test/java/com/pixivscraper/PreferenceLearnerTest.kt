package com.pixivscraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「删除偏好学习」纯逻辑的 JVM 单元测试（与桌面版 pc_learn_test.py 用例对齐） */
class PreferenceLearnerTest {

    private fun w(
        vararg tags: String,
        pruned: Boolean = false,
        page: Int = 1,
        left: Int = 1,
        source: String = "test",
        removed: Boolean = false,
    ) = PreferenceLearner.WorkTags(tags.toList(), pruned, page, left, source, removed)

    @Test
    fun statsSkipIdentityTagsAndWeightByPages() {
        val works = listOf(
            w("巨乳", "天童ケイ"),
            w("巨乳"),
            w("巨乳", "全彩", pruned = true, page = 2, left = 1),   // 删 1/2 → 权重 0.5
            w("巨乳", pruned = true, page = 4, left = 3),           // 删 1/4 → 权重 0.25
            w("全彩", "巨乳"),
            w("触手", pruned = true, page = 2, left = 1),           // 样本不足
        )
        val ctx = PreferenceLearner.buildContextStats(works, "test", countPartial = true)

        assertEquals(5, ctx.counts["巨乳"]!!.seen)
        assertEquals(0.75, ctx.counts["巨乳"]!!.pruned, 1e-9)
        assertTrue("身份标签不应参与统计", ctx.counts.keys.none { it == "天童ケイ" })
        assertTrue("巨乳出现在 5/6 作品里 → 属于基础标签", "巨乳" in ctx.baseline)

        // 不传 baseline（等价于旧行为）时按删除比例计算
        val pen = PreferenceLearner.penalties(ctx.counts, 3)
        assertEquals(0.15, pen["巨乳"]!!, 1e-9)
        assertTrue("样本不足的标签不参与", "触手" !in pen)
        assertTrue("从未被删的标签不参与", "全彩" !in pen)
        assertTrue("基础标签会被排除", "巨乳" !in PreferenceLearner.penalties(ctx.counts, 3, ctx.baseline))

        val (r, tag) = PreferenceLearner.workPenalty(listOf("巨乳", "天童ケイ"), pen)
        assertEquals(0.15, r, 1e-9)
        assertEquals("巨乳", tag)
        assertEquals(0.0 to "", PreferenceLearner.workPenalty(listOf("天童ケイ"), pen))
    }

    @Test
    fun statsAreSeparatedPerSearchTag() {
        val works = listOf(
            // 搜索标签 A：巨乳只占一半，删掉 1/2 页
            w("巨乳", "全彩", source = "A"),
            w("巨乳", "全彩", source = "A"),
            w("巨乳", "全彩", pruned = true, page = 2, left = 1, source = "A"),
            w("全彩", source = "A"),
            w("全彩", source = "A"),
            w("全彩", source = "A"),
            // 搜索标签 B：巨乳只占一半，但整组都被删光（权重 1.0）
            w("巨乳", "全彩", pruned = true, page = 2, left = 0, source = "B"),
            w("巨乳", "全彩", pruned = true, page = 2, left = 0, source = "B"),
            w("巨乳", "全彩", pruned = true, page = 2, left = 0, source = "B"),
            w("全彩", source = "B"),
            w("全彩", source = "B"),
            w("全彩", source = "B"),
        )
        val ctxA = PreferenceLearner.buildContextStats(works, "A", countPartial = true)
        val ctxB = PreferenceLearner.buildContextStats(works, "B", countPartial = true)
        assertEquals(6, ctxA.total)
        assertEquals(6, ctxB.total)
        assertEquals(3, ctxA.counts["巨乳"]!!.seen)
        assertEquals(0.5, ctxA.counts["巨乳"]!!.pruned, 1e-9)
        assertEquals(1.5, ctxB.counts["巨乳"]!!.pruned, 1e-9)   // 每条封顶 0.5

        assertTrue("全彩出现在所有作品里 → 基础标签", "全彩" in ctxA.baseline)
        val penA = PreferenceLearner.penalties(ctxA.counts, 3, ctxA.baseline)
        val penB = PreferenceLearner.penalties(ctxB.counts, 3, ctxB.baseline)
        assertEquals(0.5 / 3, penA["巨乳"]!!, 1e-9)
        assertEquals(0.5, penB["巨乳"]!!, 1e-9)
        assertTrue("基础标签被排除", "全彩" !in penA && "全彩" !in penB)
    }

    @Test
    fun partialDeletionIgnoredByDefault() {
        // 清理重复图 / 无用图（同一作品只删了几页）默认不计入学习，也不再算权重
        val works = List(8) { w("巨乳", "全彩") } +
            List(2) { w("巨乳", "全彩", pruned = true, page = 10, left = 1) }
        val ctx = PreferenceLearner.buildContextStats(works, "test")     // countPartial 默认 false
        assertEquals(10, ctx.counts["巨乳"]!!.seen)
        assertEquals(0.0, ctx.counts["巨乳"]!!.pruned, 1e-9)
        // 不传 baseline，确认「没有删除权重」而不是「被当成基础标签」
        assertTrue(PreferenceLearner.penalties(ctx.counts, 3).isEmpty())

        // 开启后计入，且单条封顶 0.5
        val ctxOn = PreferenceLearner.buildContextStats(works, "test", countPartial = true)
        assertEquals(1.0, ctxOn.counts["巨乳"]!!.pruned, 1e-9)          // 2 条 × 0.5
        assertEquals(0.1, PreferenceLearner.penalties(ctxOn.counts, 3)["巨乳"]!!, 1e-9)
    }

    @Test
    fun baselineTagIsExcluded() {
        // 该标签下的角色本身就是贫乳 → 「貧乳」几乎出现在所有作品里，不应被计入不喜欢
        val works = List(8) { w("貧乳", "天童ケイ") } +
            List(2) { w("貧乳", "天童ケイ", pruned = true, page = 2, left = 1) }
        val ctx = PreferenceLearner.buildContextStats(works, "test", countPartial = true)

        assertEquals(10, ctx.total)
        assertTrue("貧乳 与搜索标签高度伴随", "貧乳" in ctx.baseline)
        val pen = PreferenceLearner.penalties(ctx.counts, 3, ctx.baseline)
        assertFalse("基础标签不应被计入不喜欢", "貧乳" in pen)
        assertEquals(0.0 to "", PreferenceLearner.workPenalty(listOf("貧乳", "天童ケイ"), pen))
    }

    @Test
    fun removedWorksCountAsStrongDislike() {
        // 整组删掉的作品 = 明确的「不喜欢」→ 权重 1.0
        val works = List(3) { w("巨乳", "全彩") } +
            listOf(w("巨乳", "全彩", removed = true, page = 2, left = 0)) +
            List(4) { w("全彩") }
        val ctx = PreferenceLearner.buildContextStats(works, "test")

        assertEquals(4, ctx.counts["巨乳"]!!.seen)
        assertEquals(1.0, ctx.counts["巨乳"]!!.pruned, 1e-9)
        assertTrue("全彩 是基础标签", "全彩" in ctx.baseline)
        val pen = PreferenceLearner.penalties(ctx.counts, 3, ctx.baseline)
        assertEquals(0.25, pen["巨乳"]!!, 1e-9)
    }

    @Test
    fun otherContextsDoNotInterfere() {
        // 历史记录属于别的搜索标签 → 本标签下不生效（避免“共用衰减词条”）
        val works = List(4) {
            w("巨乳", "全彩", pruned = true, page = 2, left = 1, source = "另一个标签")
        }
        val ctx = PreferenceLearner.buildContextStats(works, "当前标签")
        assertEquals(0, ctx.total)
        assertTrue(PreferenceLearner.penalties(ctx.counts, 3, ctx.baseline).isEmpty())
    }
}
