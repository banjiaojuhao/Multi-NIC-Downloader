package cn.banjiaojuhao.downloader.server

import cn.banjiaojuhao.downloader.engine.Engine
import java.util.*

data class ResultMsg(val success: Boolean, val msg: String, val data: Any? = null)

data class DownloadTask(val id: Int,
                        val url: String,
                        val engine: Engine,
                        var currentState: TaskState = TaskState.Downloading,
                        val createDate: Date = Date(),
                        var lastConnectDate: Date = Date()
) {
    val speed
        get() = engine.speed
    val progress
        get() = engine.downloadProgress

    enum class TaskState {
        Downloading,
        Paused,
        Finished,
        Failed
    }
}
