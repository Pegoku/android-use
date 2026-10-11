package dev.pegoku.androiduse.shell

import android.os.ParcelFileDescriptor
import java.io.File
import dev.pegoku.androiduse.IShellService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside a process spawned by Shizuku with the shell (adb) uid, so it can
 * use `input`, `screencap`, `uiautomator`, `am`, etc. without root.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(command: String, timeoutMs: Long): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = thread(name = "shell-reader") {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(output) {
                        if (output.length < MAX_OUTPUT_CHARS) output.append(buffer, 0, minOf(n, MAX_OUTPUT_CHARS - output.length))
                    }
                }
            }
        }
        val finished = process.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(1000)
        val code = if (finished) process.exitValue() else TIMEOUT_EXIT_CODE
        return synchronized(output) { "$code\n$output" }
    }

    override fun stream(command: String): ParcelFileDescriptor {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        thread(name = "shell-stream") {
            runCatching {
                val process = ProcessBuilder("sh", "-c", command).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    process.inputStream.use { it.copyTo(out, 64 * 1024) }
                }
                process.waitFor()
            }.onFailure { runCatching { writeEnd.closeWithError(it.message ?: "stream failed") } }
        }
        return readEnd
    }

    companion object {
        const val TIMEOUT_EXIT_CODE = 124
        private const val MAX_OUTPUT_CHARS = 200_000
    }
}
