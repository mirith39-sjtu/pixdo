package com.pixivscraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「删除偏好学习」纯逻辑的 JVM 单元测试（与桌面版 pc_learn_test.py 用例对齐） */
class PreferenceLearnerTest {

    private fun w(
        vararg tags: String,
        pruned: Boolean = false,
        page: Int = 1,
        left: Int = 1,
    ) = PreferenceLearner.WorkTags(tags.toList(), pruned, page, left)

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
        val stats = PreferenceLearner.buildStats(works)

        assertEquals(5, stats["巨乳"]!!.seen)
        assertEquals(0.75, stats["巨乳"]!!.pruned, 1e-9)
        assertTrue("身份标签不应参与统计", stats.keys.none { it == "天童ケイ" })

        val pen = PreferenceLearner.penalties(stats, 3)
        assertEquals(0.15, pen["巨乳"]!!, 1e-9)
        assertTrue("样本不足的标签不参与", "触手" !in pen)
        assertTrue("从未被删的标签不参与", "全彩" !in pen)

        val (r, tag) = PreferenceLearner.workPenalty(listOf("巨乳", "天童ケイ"), pen)
        assertEquals(0.15, r, 1e-9)
        assertEquals("巨乳", tag)
        assertEquals(0.0 to "", PreferenceLearner.workPenalty(listOf("天童ケイ"), pen))
    }
}
