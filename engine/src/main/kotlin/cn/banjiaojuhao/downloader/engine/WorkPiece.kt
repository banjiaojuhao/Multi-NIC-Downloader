package cn.banjiaojuhao.downloader.engine

data class WorkPiece(var start: Long, var stop: Long) : Comparable<WorkPiece> {
    override operator fun compareTo(other: WorkPiece): Int =
            ((this.stop - this.start) - (other.stop - other.start)).toInt()

    val size
        get() = stop - start + 1

    fun dichotomize(): WorkPiece? {
        if (size < 2) {
            return null
        }
        val splitPoint = start + size / 2 - 1
        val newStop = stop
        stop = splitPoint
        return WorkPiece(splitPoint + 1, newStop)
    }
}