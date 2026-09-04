package xyz.al.gradlelsp.protocol

internal fun interface ServerLogger {
    fun log(message: String)

    companion object {
        fun standardError(): ServerLogger = ServerLogger(System.err::println)
    }
}
