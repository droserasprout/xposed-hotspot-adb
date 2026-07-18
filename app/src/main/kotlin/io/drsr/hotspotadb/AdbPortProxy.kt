package io.drsr.hotspotadb

import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * TCP proxy running in system_server. Binds 0.0.0.0:FIXED_PORT and forwards
 * bytes to adbd's TLS listener on 127.0.0.1:realPort. TLS is preserved end-to-end;
 * this is just a byte pipe so clients can always use the advertised fixed port.
 */
object AdbPortProxy {
    private val lock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundToPort: Int = -1

    private val executor =
        Executors.newCachedThreadPool { r ->
            Thread(r, "HotspotAdb-Proxy-pump").apply { isDaemon = true }
        }

    fun start(realPort: Int) {
        if (realPort <= 0) return
        if (realPort == HotspotHelper.FIXED_PORT) {
            // adbd happens to bind the fixed port already; nothing to proxy.
            stop()
            return
        }
        synchronized(lock) {
            if (serverSocket?.isBound == true && boundToPort == realPort) return
            stopLocked()
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", HotspotHelper.FIXED_PORT), 50)
                serverSocket = ss
                boundToPort = realPort
                Thread({ acceptLoop(ss, realPort) }, "HotspotAdb-Proxy-accept").apply {
                    isDaemon = true
                    start()
                }
                Xp.log(
                    "HotspotAdb: proxy listening on 0.0.0.0:${HotspotHelper.FIXED_PORT} -> 127.0.0.1:$realPort",
                )
            } catch (e: BindException) {
                Xp.log("HotspotAdb: proxy bind failed (port busy?): $e")
                serverSocket = null
                boundToPort = -1
            } catch (e: Exception) {
                Xp.log("HotspotAdb: proxy start failed: $e")
                serverSocket = null
                boundToPort = -1
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val ss = serverSocket
        serverSocket = null
        boundToPort = -1
        if (ss != null) {
            try {
                ss.close()
                Xp.log("HotspotAdb: proxy stopped")
            } catch (_: IOException) {
            }
        }
    }

    private fun acceptLoop(
        ss: ServerSocket,
        realPort: Int,
    ) {
        while (!Thread.currentThread().isInterrupted && !ss.isClosed) {
            val client =
                try {
                    ss.accept()
                } catch (_: IOException) {
                    break
                }
            executor.submit { handle(client, realPort) }
        }
    }

    private fun handle(
        client: Socket,
        realPort: Int,
    ) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            upstream = Socket("127.0.0.1", realPort)
            upstream.tcpNoDelay = true
            val up = upstream
            val downTask =
                executor.submit {
                    try {
                        client.getInputStream().copyTo(up.getOutputStream(), bufferSize = 16 * 1024)
                    } catch (_: IOException) {
                    } finally {
                        closeQuietly(client)
                        closeQuietly(up)
                    }
                }
            try {
                up.getInputStream().copyTo(client.getOutputStream(), bufferSize = 16 * 1024)
            } catch (_: IOException) {
            }
            downTask.cancel(true)
        } catch (e: IOException) {
            Xp.log("HotspotAdb: proxy handle failed: $e")
        } finally {
            closeQuietly(client)
            closeQuietly(upstream)
        }
    }

    private fun closeQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (_: IOException) {
        }
    }
}
