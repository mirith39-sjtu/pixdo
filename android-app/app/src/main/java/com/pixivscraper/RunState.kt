package com.pixivscraper

/**
 * 低产提醒：扫描了太多作品的详情仍凑不够目标数量时，询问用户是否放宽点赞条件。
 * 通过 RunState.pendingAsk / answerAsk 与应用内弹窗、前台服务（通知按钮）交互。
 */
data class LowYieldAsk(
    val id: Int,
    val scanned: Int,
    val found: Int,
    val target: Int,
    val minLikes: Int,
    val suggested: Int,
    val estCount: Int,
    val sample: Int,
)

/**
 * 运行状态桥：ViewModel 写入、前台服务（ScrapeService）读取并刷新常驻通知。
 * 同进程内单例；字段用 @Volatile 保证跨线程可见。
 */
object RunState {
    /** 是否正在运行（服务靠它决定何时收起通知） */
    @Volatile var running = false

    /** 是否已进入「下载图片」阶段（决定通知是否显示进度条） */
    @Volatile var downloading = false

    /** 已下载作品数 */
    @Volatile var downloaded = 0

    /** 目标作品数 */
    @Volatile var target = 0

    /** 停止请求标志：通知里的「停止」按钮会置 true，引擎轮询它 */
    @Volatile var stopFlag = false

    /** 结束快照：ViewModel 收尾时写入，服务读取后弹「完成通知」 */
    @Volatile var endDownloaded = 0
    @Volatile var endSkipped = 0

    /** 结束原因：null = 正常完成；非空 = 出错信息（用户手动停止看 stopFlag） */
    @Volatile var endReason: String? = null

    /** 当前阶段文案（搜索 / 筛选详情进度），前台服务直接显示 */
    @Volatile var phase: String = ""

    // ---- 低产提醒（继续查找 / 放宽点赞条件）----
    const val ASK_CONTINUE = 1
    const val ASK_LOWER = 2

    /** 待用户回答的询问（null = 没有） */
    @Volatile var pendingAsk: LowYieldAsk? = null

    /** 最近一次回答：引擎按 askId 取用 */
    @Volatile var lastAnswerId = -1
    @Volatile var lastAnswerChoice = 0

    private var askSeq = 0

    fun nextAskId(): Int = ++askSeq

    fun newAsk(info: LowYieldAsk) {
        pendingAsk = info
        bump()
    }

    /** 回答（应用内弹窗 / 通知按钮都会调它）；只在 id 匹配时生效，重复点击无副作用 */
    fun answerAsk(id: Int, choice: Int) {
        if (pendingAsk?.id != id) return
        lastAnswerId = id
        lastAnswerChoice = choice
        pendingAsk = null
        bump()
    }

    /** 状态版本号：变化则通知刷新 */
    @Volatile var version = 0

    fun begin(target: Int) {
        running = true
        downloading = false
        downloaded = 0
        this.target = target
        stopFlag = false
        endDownloaded = 0
        endSkipped = 0
        endReason = null
        phase = ""
        pendingAsk = null
        lastAnswerId = -1
        lastAnswerChoice = 0
        bump()
    }

    fun enterDownload() {
        downloading = true
        bump()
    }

    fun progress(done: Int) {
        downloaded = done
        bump()
    }

    /** 更新阶段文案；变化才 bump（避免无意义刷新通知） */
    fun updatePhase(text: String) {
        if (phase != text) {
            phase = text
            bump()
        }
    }

    fun finish() {
        running = false
        downloading = false
        pendingAsk = null
        bump()
    }

    fun bump() {
        version++
    }
}
