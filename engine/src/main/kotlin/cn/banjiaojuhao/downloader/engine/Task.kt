package cn.banjiaojuhao.downloader.engine

import io.netty.buffer.ByteBuf
import mu.KotlinLogging
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.LinkedHashMap
import kotlin.math.min


/**
 * functions about one download task and downloaded file
 */

data class TaskRequest(val id: Int, val work: WorkPiece)

class Task(savePath: Path, fileSize: Long) {
    private object Configure {
        const val minSplitPieceSize = 1024 * 200L
    }

    private val savePathString = savePath.toString()
    private val configPath = Paths.get("$savePathString.cfg")
    private val resultPath = Paths.get("$savePathString.down")
    private val taskPieces = LinkedHashMap<Int, WorkPiece>()
    private val unDispatchedTaskPieces = LinkedList<Int>()
    private var nextPieceId = 0
    private var resultByteChannel: SeekableByteChannel

    var totalDownloadedSize = fileSize
    val finished
        get() = taskPieces.size == 0

    companion object {
        val logger = KotlinLogging.logger(Task::class.java.simpleName)
        private val fileNameAndSuffixPattern = Pattern.compile("""(.+)\.(.*)""")
        private fun getNameAndSuffix(path: String): Pair<String, String> {
            val matcher = fileNameAndSuffixPattern.matcher(path)
            matcher.find()
            return Pair<String, String>(matcher.group(1), matcher.group(2))
        }
    }

    init {
        if (Files.exists(configPath)
                && Files.exists(resultPath) && Files.size(resultPath) == fileSize) {
            // continue old task
            loadTaskPieces()
            resultByteChannel = Files.newByteChannel(resultPath, StandardOpenOption.WRITE)
            examFinish()
        } else {
            // start new task
            totalDownloadedSize = 0
            if (!Files.exists(resultPath)) {
                Files.createFile(resultPath)
            }
            resultByteChannel = Files.newByteChannel(resultPath, setOf(
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING))
            if (fileSize > 0) {
                resultByteChannel.position(fileSize - 1)
                val init = ByteArray(1) { 0 }
                resultByteChannel.write(ByteBuffer.wrap(init))
            }
            val workPiece = WorkPiece(0, fileSize - 1)
            if (workPiece.size <= 0) {
                throw IllegalArgumentException("broken configure file")
            }
            taskPieces[nextPieceId] = workPiece
            unDispatchedTaskPieces.add(nextPieceId)
            nextPieceId++
        }
    }

    fun saveProgress(pieceId: Int, downloaded: ByteBuf): Boolean {
        var continueDownload = true
        val currentPiece = taskPieces[pieceId]
                ?: throw java.lang.IllegalArgumentException("illegal piece id")
        resultByteChannel.position(currentPiece.start)
        val bytesToWrite = min(downloaded.readableBytes(), currentPiece.size.toInt())
        val writtenSize = resultByteChannel.write(downloaded.nioBuffer(downloaded.readerIndex(), bytesToWrite))
        if (writtenSize != bytesToWrite) {
            logger.error { "writtenSize != bytesToWrite in task $savePathString" }
            throw IOException("failed to write to file")
        }
        currentPiece.start += writtenSize
        if (currentPiece.size <= 0L) {
            taskPieces.remove(pieceId)
            continueDownload = false
        }
        flushTaskPieces()
        totalDownloadedSize += bytesToWrite
        examFinish()
        return continueDownload
    }

    private fun examFinish() {
        if (finished) {
            resultByteChannel.close()
            if (Files.exists(Paths.get(savePathString))) {
                val (name, suffix) = getNameAndSuffix(savePathString)
                var extName = 1
                while (true) {
                    val newSavePath = "$name${'_'}${extName++}.$suffix"
                    if (!Files.exists(Paths.get(newSavePath))) {
                        Files.move(resultPath, Paths.get(newSavePath))
                        break
                    }
                }
            } else {
                Files.move(resultPath, Paths.get(savePathString))
            }
            Files.delete(configPath)
        }
    }

    fun getTaskPiece(): TaskRequest? {
//        println("${taskPieces.size} left")
        if (unDispatchedTaskPieces.isNotEmpty()) {
            val toDoPieceId = unDispatchedTaskPieces.pop()
            return TaskRequest(toDoPieceId, taskPieces[toDoPieceId]!!)
        }
        if (taskPieces.isEmpty()) {
            return null
        }
        val (_, maxPiece) = taskPieces.maxBy { it.value.size } ?: return null
        if (maxPiece.size < Configure.minSplitPieceSize) {
            return null
        }
        val newPiece = maxPiece.dichotomize() ?: return null
        taskPieces[nextPieceId++] = newPiece
        return TaskRequest(nextPieceId - 1, newPiece)
    }

    fun releaseTaskPiece(pieceId: Int) {
        if (pieceId in unDispatchedTaskPieces) {
            throw IllegalArgumentException("pieceId already released")
        }
        unDispatchedTaskPieces.add(pieceId)
    }

    private fun flushTaskPieces() {
        val output = ByteBuffer.allocate(taskPieces.size * Long.SIZE_BYTES * 2 + Int.SIZE_BYTES * 2)
        var checksum: Long = 0
        output.putInt(taskPieces.size)
        taskPieces.values.forEach {
            output.putLong(it.start)
            output.putLong(it.stop)
            checksum += it.start
            checksum += it.stop
        }
        output.putInt(checksum.toInt())
        output.flip()
        Files.newByteChannel(configPath, setOf(StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)).use {
            it.write(output)
        }
    }

    private fun loadTaskPieces() {
        Files.newByteChannel(configPath).use {
            val input = ByteBuffer.allocate(Files.size(configPath).toInt())
            it.read(input)
            input.flip()
            taskPieces.clear()
            var checksum: Long = 0
            val totalPieces = input.int
            repeat(totalPieces) {
                val workPiece = WorkPiece(input.long, input.long)
                totalDownloadedSize -= workPiece.size
                if (workPiece.size <= 0) {
                    throw IllegalArgumentException("broken configure file")
                }
                checksum += workPiece.start
                checksum += workPiece.stop
                taskPieces[nextPieceId] = workPiece
                unDispatchedTaskPieces.add(nextPieceId)
                nextPieceId++
            }
            val gotChecksum = input.int
            if (gotChecksum != checksum.toInt()) {
                throw IllegalArgumentException("broken configure file")
            }
        }
    }

}