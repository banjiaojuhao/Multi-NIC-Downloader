package test

import cn.banjiaojuhao.downloader.engine.Engine
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.nio.file.Paths

fun main() = runBlocking {
    val netcardList = Engine.getAvailableNetcard().associateBy({ it.address }, { 5 })
    val url = "https://sm.myapp.com/original/game/CSO_FullInstaller_OBT_CHN141203_01.exe"
//    val url = "https://dl.softmgr.qq.com/original/im/QQ9.1.3.25323.exe"
//    val nioEventLoopGroup = NioEventLoopGroup(2)
    val nioEventLoopGroup = NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2)
    val engine = Engine(
            url = URL(url),
            headers = null,
            fileSize = -1,
            netcards = netcardList,
            savePath = Paths.get("qq.exe"),
            eventLoopGroup = nioEventLoopGroup
    )
    launch {
        engine.start()
    }
    repeat(6000) {
        delay(1000)
        println("speed: ${engine.speed} B/s, progress ${engine.downloadProgress}")
    }
    engine.stop()
    println("exit")
}