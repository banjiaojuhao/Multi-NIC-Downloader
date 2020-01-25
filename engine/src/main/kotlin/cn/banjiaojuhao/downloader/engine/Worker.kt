package cn.banjiaojuhao.downloader.engine

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random
import io.netty.channel.Channel as nettyChannel
import kotlinx.coroutines.channels.Channel as coChannel

sealed class WorkerMsgType
data class Location(val newUrl: String) : WorkerMsgType()
data class Failed(val cause: Throwable) : WorkerMsgType()
data class Finished(val TaskPieceId: Int) : WorkerMsgType()
data class Result(val TaskPieceId: Int, val result: ByteBuf) : WorkerMsgType()
data class WorkerMsg(val worker: Worker, val msg: WorkerMsgType)

data class StopLeft(val taskPieceId: Int, val netcard: String)

class Worker(private val url: URL,
             private val headers: Map<String, String>?,
             val taskRequest: TaskRequest,
             private val msgChannel: coChannel<WorkerMsg>,
             private val netcard: String,
             private val eventLoopGroup: NioEventLoopGroup) {
    companion object {
        @UseExperimental(ObsoleteCoroutinesApi::class)
        val channelContext = newSingleThreadContext("worker")
        val logger = KotlinLogging.logger(Worker::class.java.simpleName)
    }

    private lateinit var bootstrap: Bootstrap
    private var downloadChannel: nettyChannel? = null
    @Volatile
    private var toStop = false

    suspend fun start() = suspendCoroutine<Boolean> { continuation ->
        var port = url.port
        val protocol = url.protocol.toLowerCase()
        if (port == -1) {
            if ("http" == protocol) {
                port = 80
            } else if ("https" == protocol) {
                port = 443
            }
        }
        bootstrap = Bootstrap()
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel::class.java)

        val sslCtx: SslContext?
        when (protocol) {
            "http" -> sslCtx = null
            "https" -> sslCtx = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build()
            else -> {
                continuation.resumeWithException(IllegalArgumentException("unknown scheme $protocol"))
                return@suspendCoroutine
            }
        }
        bootstrap.handler(HttpChannelInitializerImpl(sslCtx, HttpContentHandler(taskRequest, this)))

        val connect = bootstrap.connect(InetSocketAddress(url.host, port), InetSocketAddress(netcard, Random.nextInt(10000, 65535)))
//        val connect = bootstrap.connect(InetSocketAddress(url.host, port), InetSocketAddress(netcard, 6666))
        // todo address already in use
        connect.addListener {
            val success = try {
                connect.isSuccess
            } catch (e: Exception) {
                println("sss")
                continuation.resume(false)
                return@addListener
            }
            if (success) {
                val channel = connect.channel()
                downloadChannel = channel
                val request = DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, url.file)
                request.headers().set(HttpHeaderNames.HOST, url.host)
                request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP)
                request.headers().set(HttpHeaderNames.RANGE, "bytes=${taskRequest.work.start}-${taskRequest.work.stop}")

                headers?.forEach {
                    request.headers().set(it.key, it.value)
                }

                val flush = channel.writeAndFlush(request)
                flush.addListener {
                    if (flush.isSuccess) {
                        continuation.resume(true)
                    } else {
                        runBlocking {
                            msgChannel.send(WorkerMsg(this@Worker, Failed(it.cause()!!)))
                        }
                        continuation.resume(false)
                    }
                }
            } else {
                if (connect.cause().message?.contains("Address already in use: bind")!!) {
                    runBlocking {
                        msgChannel.send(WorkerMsg(this@Worker, Failed(connect.cause()!!)))
                    }
                    continuation.resume(false)
                } else {
//                    continuation.resumeWithException(connect.cause())
                    runBlocking {
                        msgChannel.send(WorkerMsg(this@Worker, Failed(connect.cause()!!)))
                    }
                    continuation.resume(false)
                }
            }
        }
    }

    suspend fun stop() = suspendCoroutine<StopLeft?> { continuation ->
        toStop = true
        if (downloadChannel != null) {
            val closeFuture = downloadChannel!!.closeFuture()
            downloadChannel = null
            continuation.resume(StopLeft(taskRequest.id, netcard))
//            closeFuture.addListener {
//                if (closeFuture.isSuccess) {
//                    continuation.resume(StopLeft(taskRequest.id, netcard))
//                } else {
////                    continuation.resumeWithException(closeFuture.cause())
//                    continuation.resume(null)
//                }
//            }
        } else {
            continuation.resume(null)
        }
    }

    private class HttpChannelInitializerImpl(val sslCtx: SslContext?,
                                             val contentHandler: HttpContentHandler) : ChannelInitializer<nettyChannel>() {
        override fun initChannel(ch: nettyChannel) {
            val pipeline = ch.pipeline()
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc()))
            }
            pipeline.addLast(HttpClientCodec())
            pipeline.addLast(HttpContentDecompressor())
            pipeline.addLast(IdleStateHandler(10, 10, 10))
            pipeline.addLast(contentHandler)
        }
    }

    @ChannelHandler.Sharable
    private class HttpContentHandler(val taskPiece: TaskRequest,
                                     val worker: Worker
    ) : SimpleChannelInboundHandler<HttpObject>(false) {
        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
            ctx?.close()
            if (!worker.toStop) {
                runBlocking {
                    worker.msgChannel.send(WorkerMsg(worker, Failed(cause!!)))
                }
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext?, evt: Any?) {
            super.userEventTriggered(ctx, evt)
            when (evt) {
                is IdleStateEvent -> {
                    ReferenceCountUtil.release(evt)
                    ctx!!.close()
                    runBlocking {
                        worker.msgChannel.send(WorkerMsg(worker,
                                Failed(IOException("timeout of worker ${worker.taskRequest.id}"))))
                    }
                }
            }
        }

        override fun channelRead0(ctx: ChannelHandlerContext?, msg: HttpObject?) {
            if (worker.toStop) {
                ReferenceCountUtil.release(msg)
                ctx!!.close()
                return
            }
            when (msg) {
                is HttpResponse -> {
                    val location = msg.headers().get(HttpHeaderNames.LOCATION)

                    location?.let {
                        runBlocking {
                            ctx!!.close()
                            worker.msgChannel.send(WorkerMsg(worker, Location(it)))
                        }
                        ReferenceCountUtil.release(msg)
                        return
                    }
                    val contentLength = msg.headers().get(HttpHeaderNames.CONTENT_LENGTH)
                    if (contentLength.toInt() == 0) {
                        logger.warn { "content_length is zero of task ${worker.taskRequest.id} ${worker.url}" }
                    }
                    val contentRange = msg.headers().get(HttpHeaderNames.CONTENT_RANGE)
                    logger.info { "content_range of task ${worker.taskRequest.id} $contentRange" }
                    ReferenceCountUtil.release(msg)
                }

                is HttpContent -> {
                    if (msg == LastHttpContent.EMPTY_LAST_CONTENT) {
                        ReferenceCountUtil.release(msg)
                        return
                    }
                    runBlocking {
                        //                        if (taskPiece.work.size < msg.content().readableBytes()) {
//                            throw IllegalStateException("downloaded > work size")
//                        }
                        worker.msgChannel.send(WorkerMsg(worker, Result(taskPiece.id, msg.content())))

                        if (msg is LastHttpContent) {
                            ctx!!.close()
                            worker.msgChannel.send(WorkerMsg(worker, Finished(taskPiece.id)))
                        }
                    }
                }
            }
        }
    }
}


