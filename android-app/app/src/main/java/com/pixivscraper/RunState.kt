package com.pixivscraper

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

    fun finish() {
        running = false
        downloading = false
        bump()
    }

    fun bump() {
        version++
    }
}
