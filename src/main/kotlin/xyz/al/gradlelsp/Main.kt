package xyz.al.gradlelsp

import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    val exitCode = GradleLspApplication().run(arguments, System.`in`, System.out, System.err)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
