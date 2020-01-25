package cn.banjiaojuhao.downloader.server

import cn.banjiaojuhao.downloader.engine.Engine
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import java.net.URL
import java.nio.file.Paths
import java.util.*
import javax.annotation.PostConstruct
import kotlin.collections.HashMap


@Service
class DownloadService {
    private var taskList = HashMap<Int, DownloadTask>()
    private val nioEventLoopGroup = NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2)
    private val executor = newSingleThreadContext(DownloadService::class.java.name)

    @PostConstruct
    fun init() {
        // init code goes here
    }

    internal fun status(): ResultMsg {
        val stateList = ArrayList<DownloadTask>(taskList.size)
        taskList.values.forEach {


            stateList.add(it)
        }
        return ResultMsg(true, "success", stateList)
    }

    internal fun createTask(url: String): ResultMsg {
        val netcardList = Engine.getAvailableNetcard().associateBy({ it.address }, { 5 })

        val parsedUrl = URL(url)
        val engine = Engine(
                url = parsedUrl,
                headers = null,
                fileSize = -1,
                netcards = netcardList,
                savePath = Paths.get(parsedUrl.path.substringAfterLast("/")),
                eventLoopGroup = nioEventLoopGroup
        )
        val taskId = Random().nextInt()
        val newTask = DownloadTask(
                id = taskId,
                engine = engine,
                url = url
        )
        taskList[taskId] = newTask
        Thread {
            runBlocking {
                launch(executor) {
                    engine.start()
                }
            }
        }.start()
        return ResultMsg(true, "created task successfully")
    }

    internal fun deleteTask(id: Int): ResultMsg {
        if (taskList.containsKey(id)) {
            runBlocking {
                launch(executor) {
                    taskList[id]!!.engine.stop()
                }
            }
            taskList.remove(id)
            return ResultMsg(true, "deleted task")
        } else {
            return ResultMsg(false, "delete failed, invalid id")
        }
    }

    internal fun pauseTask(id: Int): ResultMsg {
        if (taskList.containsKey(id)) {
            runBlocking {
                launch(executor) {
                    taskList[id]!!.engine.stop()
                    taskList[id]!!.currentState = DownloadTask.TaskState.Paused
                }
            }
            return ResultMsg(true, "paused task")
        } else {
            return ResultMsg(false, "pause failed, invalid id")
        }
    }

    internal fun continueTask(id: Int): ResultMsg {
        if (taskList.containsKey(id)) {
            val netcardList = Engine.getAvailableNetcard().associateBy({ it.address }, { 5 })

            val parsedUrl = URL(taskList[id]!!.url)
            val engine = Engine(
                    url = parsedUrl,
                    headers = null,
                    fileSize = -1,
                    netcards = netcardList,
                    savePath = Paths.get(parsedUrl.path.substringAfterLast("/")),
                    eventLoopGroup = nioEventLoopGroup
            )
            runBlocking {
                launch(executor) {
                    taskList[id]!!.engine.start()
                    taskList[id]!!.currentState = DownloadTask.TaskState.Downloading
                }
            }
            return ResultMsg(true, "continued task")
        } else {
            return ResultMsg(false, "continue failed, invalid id")
        }
    }

}
