/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.webtransfer

import android.os.Environment
import android.util.Log
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.util.getLocalAddress
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.hierynomus.smbj.auth.AuthenticationContext
import me.zhanghai.android.files.storage.canonicalizeHost

object WebTransferServer {
    private const val TAG = "WebTransferServer"
    const val DEFAULT_PORT = 8888
    private const val MAX_PORT_ATTEMPTS = 20

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val isRunning = AtomicBoolean(false)
    private var currentPort: Int = DEFAULT_PORT
    private var cachedHtml: String? = null

    val downloadDirectory: File
        get() {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return if (dir.exists()) dir else File("/storage/emulated/0/Download").apply { mkdirs() }
        }

    fun isServerRunning(): Boolean = isRunning.get() && serverSocket != null && !serverSocket!!.isClosed

    fun getPort(): Int = currentPort

    fun getIpAddress(): String? {
        return try {
            InetAddress::class.getLocalAddress()?.hostAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP: ${e.message}")
            null
        }
    }

    fun getAccessUrl(): String {
        val ip = getIpAddress() ?: "127.0.0.1"
        return "http://$ip:$currentPort/"
    }

    private fun getWebHtml(): String {
        cachedHtml?.let { return it }
        val html = try {
            application.assets.open("webtransfer/index.html").bufferedReader(StandardCharsets.UTF_8).use {
                it.readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load webtransfer/index.html from assets", e)
            "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>Material Files</title></head><body><h1>Material Files Web Transfer</h1><p>Running on port $currentPort</p></body></html>"
        }
        cachedHtml = html
        return html
    }

    private var cachedStorageHtml: String? = null

    private fun getStorageHtml(): String {
        cachedStorageHtml?.let { return it }
        val html = try {
            application.assets.open("webtransfer/storage.html").bufferedReader(StandardCharsets.UTF_8).use {
                it.readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load webtransfer/storage.html from assets", e)
            "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>Add Storage</title></head><body><h1>Add Storage</h1></body></html>"
        }
        cachedStorageHtml = html
        return html
    }

    @Synchronized
    fun start(): Boolean {
        if (isServerRunning()) {
            Log.d(TAG, "Server already running on port $currentPort")
            return true
        }

        stop()

        for (i in 0 until MAX_PORT_ATTEMPTS) {
            val port = DEFAULT_PORT + i
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = ss
                currentPort = port
                isRunning.set(true)

                val pool = Executors.newCachedThreadPool()
                executor = pool

                pool.execute {
                    Log.i(TAG, "WebTransferServer listening on 0.0.0.0:$port")
                    while (isRunning.get() && !ss.isClosed) {
                        try {
                            val client = ss.accept()
                            pool.execute { handleClient(client) }
                        } catch (e: Exception) {
                            if (!isRunning.get()) break
                            Log.w(TAG, "Error accepting client: ${e.message}")
                        }
                    }
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Port $port unavailable: ${e.message}")
            }
        }
        return false
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            executor?.shutdownNow()
        } catch (_: Exception) {}
        executor = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val headerLines = mutableListOf<String>()
            val lineBuf = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) break
                val c = b.toChar()
                if (c == '\r') {
                    val next = input.read()
                    if (next == '\n'.code) {
                        val line = lineBuf.toString()
                        lineBuf.setLength(0)
                        if (line.isEmpty()) break
                        headerLines.add(line)
                    } else {
                        lineBuf.append(c)
                        if (next != -1) lineBuf.append(next.toChar())
                    }
                } else {
                    lineBuf.append(c)
                }
            }

            if (headerLines.isEmpty()) {
                socket.close()
                return
            }

            val requestLine = headerLines[0].split(" ")
            if (requestLine.size < 2) {
                socket.close()
                return
            }
            val method = requestLine[0].uppercase(Locale.US)
            val rawPath = requestLine[1]

            val headers = mutableMapOf<String, String>()
            for (i in 1 until headerLines.size) {
                val idx = headerLines[i].indexOf(':')
                if (idx > 0) {
                    val k = headerLines[i].substring(0, idx).trim().lowercase(Locale.US)
                    val v = headerLines[i].substring(idx + 1).trim()
                    headers[k] = v
                }
            }

            val path: String
            val queryString: String
            val qIdx = rawPath.indexOf('?')
            if (qIdx >= 0) {
                path = rawPath.substring(0, qIdx)
                queryString = rawPath.substring(qIdx + 1)
            } else {
                path = rawPath
                queryString = ""
            }

            val queryParams = parseQuery(queryString)
            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            when {
                (method == "GET" || method == "HEAD") && (path == "/" || path == "/index.html") -> {
                    sendHtmlResponse(output, getWebHtml(), method == "HEAD")
                }
                (method == "GET" || method == "HEAD") && (path == "/storage" || path == "/storage.html") -> {
                    sendHtmlResponse(output, getStorageHtml(), method == "HEAD")
                }
                method == "GET" && path == "/api/status" -> {
                    val totalSpace = downloadDirectory.totalSpace
                    val freeSpace = downloadDirectory.freeSpace
                    val json = JSONObject().apply {
                        put("status", "running")
                        put("port", currentPort)
                        put("ip", getIpAddress() ?: "unknown")
                        put("directory", downloadDirectory.absolutePath)
                        put("totalSpace", totalSpace)
                        put("freeSpace", freeSpace)
                    }
                    sendJsonResponse(output, json.toString())
                }
                method == "GET" && path == "/api/files" -> {
                    val files = downloadDirectory.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
                    val array = JSONArray()
                    val sorted = files.sortedByDescending { it.lastModified() }
                    for (f in sorted) {
                        val item = JSONObject().apply {
                            put("name", f.name)
                            put("size", f.length())
                            put("isDirectory", f.isDirectory)
                            put("modified", f.lastModified())
                        }
                        array.put(item)
                    }
                    sendJsonResponse(output, array.toString())
                }
                method == "GET" && path == "/api/download" -> {
                    val rawName = queryParams["name"]
                    if (rawName.isNullOrEmpty()) {
                        sendError(output, 400, "Missing filename")
                        return
                    }
                    val safeName = File(rawName).name
                    val file = File(downloadDirectory, safeName)
                    if (!file.exists() || !file.isFile) {
                        sendError(output, 404, "File not found")
                        return
                    }
                    sendFileResponse(output, file)
                }
                method == "POST" && path == "/api/upload" -> {
                    val rawName = queryParams["name"] ?: headers["x-filename"] ?: "upload_${System.currentTimeMillis()}"
                    val safeName = File(rawName).name.replace(Regex("[/\\\\]"), "_")
                    val targetFile = File(downloadDirectory, safeName)

                    try {
                        FileOutputStream(targetFile).use { fos ->
                            var remaining = contentLength
                            val buffer = ByteArray(65536)
                            if (contentLength > 0) {
                                while (remaining > 0) {
                                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                                    val read = input.read(buffer, 0, toRead)
                                    if (read == -1) break
                                    fos.write(buffer, 0, read)
                                    remaining -= read
                                }
                            } else {
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                }
                            }
                            fos.flush()
                        }
                        Log.i(TAG, "Uploaded file saved: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                        val json = JSONObject().apply {
                            put("success", true)
                            put("name", targetFile.name)
                            put("size", targetFile.length())
                        }
                        sendJsonResponse(output, json.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Upload failed: ${e.message}", e)
                        sendError(output, 500, "Upload failed: ${e.message}")
                    }
                }
                method == "POST" && path == "/api/delete" -> {
                    val rawName = queryParams["name"]
                    if (rawName.isNullOrEmpty()) {
                        sendError(output, 400, "Missing filename")
                        return
                    }
                    val safeName = File(rawName).name
                    val file = File(downloadDirectory, safeName)
                    val deleted = if (file.exists()) file.delete() else false
                    val json = JSONObject().apply {
                        put("success", deleted)
                    }
                    sendJsonResponse(output, json.toString())
                }
                method == "POST" && path == "/api/storage/add" -> {
                    val bodyString = readBodyString(input, contentLength)
                    try {
                        val json = JSONObject(bodyString)
                        val type = json.optString("type", "smb")
                        val name = json.optString("name", "").trim()
                        val rawHost = json.optString("host", "").trim()
                        val rawPort = json.optInt("port", 0)
                        val rawPath = json.optString("path", "").trim()
                        val username = json.optString("username", "").trim()
                        val password = json.optString("password", "")
                        val domain = json.optString("domain", "").trim()
                        var ssl = json.optBoolean("ssl", false)

                        var parsedHost = rawHost
                        var parsedPort = rawPort
                        var parsedPath = rawPath

                        // 1. Strip protocol scheme if present (e.g. http://, https://, davs://, smb://, ftp://, sftp://)
                        val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+-.]*://")
                        val schemeMatch = schemeRegex.find(parsedHost)
                        if (schemeMatch != null) {
                            val scheme = schemeMatch.value.removeSuffix("://").lowercase(Locale.US)
                            if (scheme == "https" || scheme == "davs") {
                                ssl = true
                            } else if (scheme == "http" || scheme == "dav") {
                                ssl = false
                            }
                            parsedHost = parsedHost.substring(schemeMatch.range.last + 1)
                        }

                        // 2. Extract path if embedded in host (e.g. 192.168.1.100:5244/dav)
                        val slashIndex = parsedHost.indexOf('/')
                        if (slashIndex != -1) {
                            val embeddedPath = parsedHost.substring(slashIndex + 1).trim()
                            parsedHost = parsedHost.substring(0, slashIndex).trim()
                            if (parsedPath.isEmpty() && embeddedPath.isNotEmpty()) {
                                parsedPath = embeddedPath
                            }
                        }

                        // 3. Extract port if embedded in host (handling IPv6 [::1]:port properly)
                        if (parsedHost.startsWith('[') && parsedHost.contains("]:")) {
                            val closeBracket = parsedHost.indexOf("]:")
                            val portStr = parsedHost.substring(closeBracket + 2)
                            parsedHost = parsedHost.substring(1, closeBracket)
                            portStr.toIntOrNull()?.let { if (it in 1..65535) parsedPort = it }
                        } else if (!parsedHost.startsWith('[') && parsedHost.contains(':')) {
                            val colonIndex = parsedHost.lastIndexOf(':')
                            val portStr = parsedHost.substring(colonIndex + 1)
                            val possibleHost = parsedHost.substring(0, colonIndex)
                            val possiblePort = portStr.toIntOrNull()
                            if (possiblePort != null && possiblePort in 1..65535) {
                                parsedHost = possibleHost
                                parsedPort = possiblePort
                            }
                        }

                        parsedHost = parsedHost.trim().trim('/', ':')
                        if (parsedHost.isEmpty()) {
                            sendError(output, 400, "主机/IP不能为空")
                            return
                        }

                        val canonicalHost = URI::class.canonicalizeHost(parsedHost)
                        val cleanPath = parsedPath.removePrefix("/").removeSuffix("/")
                        val cleanName = name.takeIf { it.isNotEmpty() }

                        val server: me.zhanghai.android.files.storage.Storage = when (type.lowercase(Locale.US)) {
                            "smb" -> {
                                val effectivePort = if (parsedPort in 1..65535) parsedPort else 445
                                val smbUsername: String
                                val smbPassword: String
                                val smbDomain: String?
                                if (username.isEmpty() || username.equals("guest", ignoreCase = true)) {
                                    val guest = AuthenticationContext.guest()
                                    smbUsername = guest.username
                                    smbPassword = guest.password.concatToString()
                                    smbDomain = guest.domain.takeIf { it.isNotEmpty() } ?: domain.takeIf { it.isNotEmpty() }
                                } else {
                                    smbUsername = username
                                    smbPassword = password
                                    smbDomain = domain.takeIf { it.isNotEmpty() }
                                }
                                val authority = me.zhanghai.android.files.provider.smb.client.Authority(
                                    host = canonicalHost,
                                    port = effectivePort,
                                    username = smbUsername,
                                    domain = smbDomain
                                )
                                me.zhanghai.android.files.storage.SmbServer(
                                    id = null,
                                    customName = cleanName,
                                    authority = authority,
                                    password = smbPassword,
                                    relativePath = cleanPath
                                )
                            }
                            "webdav" -> {
                                val protocol = if (ssl) {
                                    me.zhanghai.android.files.provider.webdav.client.Protocol.DAVS
                                } else {
                                    me.zhanghai.android.files.provider.webdav.client.Protocol.DAV
                                }
                                val effectivePort = if (parsedPort in 1..65535) parsedPort else protocol.defaultPort
                                val authority = me.zhanghai.android.files.provider.webdav.client.Authority(
                                    protocol = protocol,
                                    host = canonicalHost,
                                    port = effectivePort,
                                    username = username
                                )
                                val authentication = if (password.isNotEmpty()) {
                                    me.zhanghai.android.files.provider.webdav.client.PasswordAuthentication(password)
                                } else {
                                    me.zhanghai.android.files.provider.webdav.client.NoneAuthentication
                                }
                                me.zhanghai.android.files.storage.WebDavServer(
                                    id = null,
                                    customName = cleanName,
                                    authority = authority,
                                    authentication = authentication,
                                    relativePath = cleanPath
                                )
                            }
                            "ftp" -> {
                                val effectivePort = if (parsedPort in 1..65535) parsedPort else 21
                                val authority = me.zhanghai.android.files.provider.ftp.client.Authority(
                                    protocol = me.zhanghai.android.files.provider.ftp.client.Protocol.FTP,
                                    host = canonicalHost,
                                    port = effectivePort,
                                    username = if (username.isNotEmpty()) username else me.zhanghai.android.files.provider.ftp.client.Authority.ANONYMOUS_USERNAME,
                                    mode = me.zhanghai.android.files.provider.ftp.client.Mode.PASSIVE,
                                    encoding = StandardCharsets.UTF_8.name()
                                )
                                me.zhanghai.android.files.storage.FtpServer(
                                    id = null,
                                    customName = cleanName,
                                    authority = authority,
                                    password = password,
                                    relativePath = cleanPath
                                )
                            }
                            "sftp" -> {
                                val effectivePort = if (parsedPort in 1..65535) parsedPort else 22
                                val authority = me.zhanghai.android.files.provider.sftp.client.Authority(
                                    host = canonicalHost,
                                    port = effectivePort,
                                    username = username
                                )
                                val authentication = me.zhanghai.android.files.provider.sftp.client.PasswordAuthentication(password)
                                me.zhanghai.android.files.storage.SftpServer(
                                    id = null,
                                    customName = cleanName,
                                    authority = authority,
                                    authentication = authentication,
                                    relativePath = cleanPath
                                )
                            }
                            else -> {
                                sendError(output, 400, "不支持的存储类型: $type")
                                return
                            }
                        }

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            me.zhanghai.android.files.storage.Storages.addOrReplace(server)
                            me.zhanghai.android.files.storage.LanStorageEventBus.lastAddedStorage.value = server
                        }

                        Log.i(TAG, "Storage added: type=$type, authority=${server.description}, path=$cleanPath")

                        val resp = JSONObject().apply {
                            put("success", true)
                            put("name", server.customName ?: server.getDefaultName(application))
                            put("authority", server.description)
                        }
                        sendJsonResponse(output, resp.toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Add storage error: ${e.message}", e)
                        sendError(output, 500, "添加存储失败: ${e.message}")
                    }
                }
                else -> {
                    sendError(output, 404, "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client handle error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in qs.split("&")) {
            val parts = param.split("=")
            if (parts.isNotEmpty()) {
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                result[key] = value
            }
        }
        return result
    }

    private fun readBodyString(input: InputStream, len: Long): String {
        if (len <= 0 || len > 1048576) return ""
        val bytes = ByteArray(len.toInt())
        var readTotal = 0
        while (readTotal < bytes.size) {
            val r = input.read(bytes, readTotal, bytes.size - readTotal)
            if (r == -1) break
            readTotal += r
        }
        return String(bytes, 0, readTotal, StandardCharsets.UTF_8)
    }

    private fun sendHtmlResponse(output: OutputStream, html: String, isHead: Boolean = false) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        if (!isHead) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun sendJsonResponse(output: OutputStream, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun sendFileResponse(output: OutputStream, file: File) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Content-Disposition: attachment; filename=\"${file.name}\"\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        FileInputStream(file).use { fis ->
            val buf = ByteArray(65536)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
            }
        }
        output.flush()
    }

    private fun sendError(output: OutputStream, code: Int, msg: String) {
        val body = """{"error":"$msg"}""".toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $code $msg\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(body)
        output.flush()
    }
}
