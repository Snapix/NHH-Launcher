package com.snapix.nhhlauncher.cast.media

import com.snapix.nhhlauncher.cast.util.logI
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class HttpStreamServer(
    private val host: String,
    private val port: Int,
    private val segmenter: HlsSegmenter,
    private val token: String,
    private val resolution: Resolution,
) {
    private var socket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        socket = ServerSocket(port, 32, InetAddress.getByName(host))
        executor.execute {
            while (running) {
                val client = runCatching { socket?.accept() }.getOrNull() ?: break
                executor.execute { handle(client) }
            }
        }
        logI("HTTP cast server listening on $host:$port")
    }

    private fun handle(client: Socket?) {
        if (client == null) return
        client.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readRequest(input) ?: return
            val parts = request.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                respond(output, 405, "text/plain", "method not allowed".toByteArray())
                return
            }
            val path = parts[1].substringBefore('?')
            val prefix = "/c/$token/"
            if (!path.startsWith(prefix)) {
                respond(output, 404, "text/plain", "not found".toByteArray())
                return
            }
            val resource = path.removePrefix(prefix)
            when {
                resource == "stream.m3u8" -> respond(output, 200, "application/vnd.apple.mpegurl", masterPlaylist.toByteArray())
                resource == "media.m3u8" -> respond(output, 200, "application/vnd.apple.mpegurl", segmenter.playlist().toByteArray())
                resource.startsWith("seg-") && resource.endsWith(".ts") -> {
                    val seq = resource.removePrefix("seg-").removeSuffix(".ts").toIntOrNull()
                    val bytes = seq?.let { segmenter.getSegment(it) }
                    if (bytes == null) respond(output, 404, "text/plain", "not found".toByteArray())
                    else respond(output, 200, "video/mp2t", bytes)
                }
                resource == "health" -> respond(output, 200, "text/plain", "OK".toByteArray())
                else -> respond(output, 404, "text/plain", "not found".toByteArray())
            }
        }
    }

    private fun readRequest(input: BufferedInputStream): String? {
        val out = StringBuilder()
        var last = 0
        var count = 0
        while (count++ < 8192) {
            val b = input.read()
            if (b < 0) return null
            out.append(b.toChar())
            if (last == '\r'.code && b == '\n'.code && out.endsWith("\r\n\r\n")) break
            last = b
        }
        return out.toString().lineSequence().firstOrNull()
    }

    private fun respond(output: BufferedOutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: " + body.size + "\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        executor.shutdownNow()
    }

    private val masterPlaylist: String
        get() {
            val avcLevel = when {
                resolution.width * resolution.height >= 1920 * 1080 -> "28"
                resolution.width * resolution.height >= 1280 * 720 -> "1F"
                else -> "1E"
            }
            return "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=" + resolution.bitrate +
                ",CODECS=\"avc1.42E0" + avcLevel + "\"" +
                ",RESOLUTION=" + resolution.width + "x" + resolution.height +
                ",NAME=\"" + resolution.label + "\"\n" +
                "media.m3u8\n"
        }
}
