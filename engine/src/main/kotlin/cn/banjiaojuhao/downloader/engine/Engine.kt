package cn.banjiaojuhao.downloader.engine

import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ticker
import mu.KotlinLogging
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

data class Netcard(val name: String, val address: String)

/**
 * download module
 * load download progress if there is
 */
class Engine(private val url: URL,
             private var headers: Map<String, String>?,
             savePath: Path,
             private var fileSize: Long,
             private val netcards: Map<String, Int>,
             private val eventLoopGroup: NioEventLoopGroup
) {
    companion object {
        val logger = KotlinLogging.logger(Engine::class.java.simpleName)
        fun getAvailableNetcard(): List<Netcard> {
            val netcardList = ArrayList<Netcard>()
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            networkInterfaces.iterator().forEach { networkInterface ->
                if (!(networkInterface.isLoopback || networkInterface.isVirtual)) {
                    val inetAddresses = networkInterface.inetAddresses
                    val displayName = networkInterface.displayName
                    networkInterface.inetAddresses.iterator().forEach { inetAddress ->
                        if (inetAddress is Inet4Address) {
                            netcardList.add(Netcard(displayName, inetAddress.getHostAddress()))
                        }
                    }
                }
            }
            return netcardList
        }

        fun getFileSize(url: URL, headers: Map<String, String>? = null): Long {
            val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
            var newFileSize: Long = -1
            connection.requestMethod = "HEAD"
            headers?.forEach {
                connection.setRequestProperty(it.key, it.value)
            }
            connection.setRequestProperty("Connection", "close")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (connection.responseCode == 200) {
                newFileSize = connection.getHeaderField("Content-Length").toLong()
            }
            connection.disconnect()
            return newFileSize
        }
    }

    private val downloadWorker = LinkedHashSet<Worker>()
    private val toStop = AtomicBoolean(false)
    private var task: Task
    private val resultChannel = Channel<WorkerMsg>(20)
    private lateinit var resultReceiver: Job

    private var lastQuerySize = 0L

    val totalDownloadedSize
        get() = task.totalDownloadedSize
    val downloadProgress
        get() = task.totalDownloadedSize.toDouble() / fileSize

    val downloading
        get() = downloadWorker.size > 0
    var speed = 0
        private set

    init {
        logger.info { "init task headers of url: $url" }
        val newHeaders =
                if (headers == null) {
                    LinkedHashMap<String, String>()
                } else {
                    LinkedHashMap(headers)
                }
        if (!newHeaders.contains("Refer")) {
            val refer = url.toString()
            newHeaders["Refer"] = refer
            logger.info { "updated http header refer: $refer" }
        }
        if (!newHeaders.contains("Origin")) {
            val origin = "${url.protocol}://${url.host}"
            newHeaders["Origin"] = origin
            logger.info { "updated http header origin: $origin" }
        }
        if (!newHeaders.contains("User-Agent")) {
            newHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 6.1; Trident/7.0; rv:11.0) like Gecko"
            logger.info { "updated http header User-Agent to default" }
        }
        logger.info { "init task headers finished" }
        if (fileSize < 0) {
            logger.info { "fileSize is $fileSize, refresh it" }
            fileSize = getFileSize(url, newHeaders)
            logger.info { "new fileSize is $fileSize" }
        }
        task = Task(savePath, fileSize)
    }

    private suspend fun resultReceiver() = coroutineScope {
        loop@ for ((worker, msg) in resultChannel) {
            if (!downloadWorker.contains(worker)) {
                logger.info { "got msg from stopped worker $msg" }
                continue
            }
            logger.info { "got msg from worker with taskPieceId ${worker.taskRequest.id} $msg" }
            if (task.finished) {
                logger.info { "task finished $url" }
                println("finished")
                break
            }
            var startNew = true
            var netcard = netcards.keys.random()
            var newUrl = url
            when (msg) {
                is Result -> {
                    val readableBytes = msg.result.readableBytes()
                    val continueDownload = task.saveProgress(msg.TaskPieceId, msg.result)
                    ReferenceCountUtil.release(msg.result)
                    if (task.finished) {
                        logger.info { "task finished $url" }
                        println("finished")
                        break@loop
                    }
                    if (continueDownload) {
                        startNew = false
                    } else {
                        logger.info { "stop worker ${worker.taskRequest.id} in msg Result" }
                        val stopLeft = worker.stop() ?: continue@loop
                        netcard = stopLeft.netcard
                        logger.info { "worker returned netcard $netcard for new worker" }
                        downloadWorker.remove(worker)
                    }
                }
                is Finished -> {
                    logger.info { "stop worker ${worker.taskRequest.id} in msg Finished" }
                    val stopLeft = worker.stop()
                    stopLeft ?: continue@loop
                    netcard = stopLeft.netcard
                    logger.info { "worker returned netcard $netcard for new worker" }
                    downloadWorker.remove(worker)
                }
                is Location -> {
                    logger.info { "stop worker ${worker.taskRequest.id} in msg Location" }
                    val stopLeft = worker.stop() ?: continue@loop
                    netcard = stopLeft.netcard
                    logger.info { "worker returned netcard $netcard for new worker" }
                    task.releaseTaskPiece(stopLeft.taskPieceId)
                    downloadWorker.remove(worker)
                    newUrl = URL(msg.newUrl)
                }
                is Failed -> {
                    logger.info { "stop worker ${worker.taskRequest.id} in msg Failed" }
                    val stopLeft = worker.stop() ?: continue@loop
                    netcard = stopLeft.netcard
                    logger.info { "worker returned netcard $netcard for new worker" }
                    task.releaseTaskPiece(stopLeft.taskPieceId)
                    downloadWorker.remove(worker)
                }
            }
            logger.info { "msg resolved; from worker with taskPieceId ${worker.taskRequest.id} $msg" }
            if (startNew) {
                val taskPiece = task.getTaskPiece()
                taskPiece ?: logger.info { "start new worker, but no more task" }
                taskPiece?.let {
                    logger.info {
                        "create new worker with taskPiece " +
                                "${taskPiece.id}:${taskPiece.work.start}-${taskPiece.work.stop}"
                    }
                    val newWorker = Worker(url = newUrl,
                            headers = headers,
                            msgChannel = resultChannel,
                            netcard = netcard,
                            taskRequest = it,
                            eventLoopGroup = eventLoopGroup
                    )
                    downloadWorker.add(newWorker)
                    launch { newWorker.start() }
                    logger.info { "worker started ${taskPiece.id}:${taskPiece.work.start}-${taskPiece.work.stop}" }
                }
            }
        }
    }

    @UseExperimental(ObsoleteCoroutinesApi::class)
    private suspend fun speedRefresher() {
        val tickerChannel = ticker(delayMillis = 2000, initialDelayMillis = 0)
        for (unit in tickerChannel) {
            val currentQuerySize = totalDownloadedSize
            if (lastQuerySize == 0L) {
                speed = 0
            } else {
                speed = (currentQuerySize - lastQuerySize).toInt() / 2
            }
            lastQuerySize = currentQuerySize
        }
    }

    suspend fun start() = coroutineScope {
        if (downloading) {
            logger.error { "start an already started task of url $url" }
            throw IllegalStateException("task have started already")
        }
        logger.info { "start task in $url" }
        toStop.set(false)
        netcards.forEach { (netcard, conCount) ->
            logger.info { "to start $netcard $conCount worker" }
            repeat(conCount) {
                val taskPiece = task.getTaskPiece() ?: return@forEach
                logger.info { "create worker: @$netcard - No.$it with piece ${taskPiece.id}:${taskPiece.work.start}-${taskPiece.work.stop}" }
                val worker = Worker(url = url,
                        headers = headers,
                        msgChannel = resultChannel,
                        netcard = netcard,
                        taskRequest = taskPiece,
                        eventLoopGroup = eventLoopGroup
                )
                logger.info { "worker started ${taskPiece.id}:${taskPiece.work.start}-${taskPiece.work.stop}" }
                launch {
                    worker.start()
                }
                downloadWorker.add(worker)
            }
        }
        launch { speedRefresher() }
        logger.info { "launched worker and speedRefresher, then into resultReceiver loop" }
        resultReceiver = launch { resultReceiver() }
    }

    suspend fun stop() = coroutineScope {
        logger.info { "stop task $url" }
        toStop.set(true)
        downloadWorker.forEach {
            launch { it.stop() }
        }
        resultReceiver.cancelAndJoin()
        logger.info { "task stopped $url" }
    }

}
