package com.example.kotlindemo

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Defensive Bluetooth helper: listens, connects, reads/writes bytes and forwards
 * raw received strings to the UI via uiCallback(tag,msg).
 *
 * All Bluetooth calls that may require runtime permissions are guarded with
 * try/catch(SecurityException) so the app doesn't crash if permission is missing.
 */
class BluetoothService(
    private val adapter: BluetoothAdapter,
    private val uiCallback: (tag: String, msg: String) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothService"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SERVICE_NAME = "BTComposeSPP"
    }

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var activeSocket: BluetoothSocket? = null
    private val exec = Executors.newCachedThreadPool()

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try { uiCallback("LOG", msg) } catch (_: Exception) {}
    }

    fun startServer() {
        stopServer()
        exec.execute {
            try {
                log("startServer: creating server socket")
                serverSocket = try {
                    adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                } catch (se: SecurityException) {
                    log("startServer: SecurityException creating server socket: ${se.message}")
                    null
                }
                if (serverSocket == null) return@execute

                log("startServer: waiting for accept()")
                val sock = try {
                    serverSocket?.accept()
                } catch (io: IOException) {
                    log("startServer: accept() failed: ${io.message}")
                    null
                } catch (se: SecurityException) {
                    log("startServer: accept() SecurityException: ${se.message}")
                    null
                }
                if (sock == null) {
                    log("startServer: no socket accepted")
                    return@execute
                }
                val remote = try { sock.remoteDevice.address } catch (_: Exception) { "unknown" }
                log("startServer: accepted from $remote")
                cleanupActiveSocket()
                activeSocket = sock
                startIo(sock)
                try { uiCallback("CONNECTED", remote) } catch (_: Exception) {}
            } catch (t: Throwable) {
                log("startServer: unexpected: ${t.message}")
            }
        }
    }

    fun stopServer() {
        log("stopServer: closing server socket")
        try { serverSocket?.close() } catch (e: Exception) { log("stopServer close err: ${e.message}") }
        serverSocket = null
    }

    fun connectTo(device: android.bluetooth.BluetoothDevice) {
        exec.execute {
            val devAddr = try { device.address } catch (_: Exception) { "unknown" }
            val devNameSafe = try { device.name } catch (se: SecurityException) { null } catch (_: Exception) { null }
            log("connectTo: connecting to $devAddr / ${devNameSafe ?: "name-unavailable"}")

            cleanupActiveSocket()
            try { adapter.cancelDiscovery() } catch (se: SecurityException) {
                log("connectTo: cancelDiscovery SecurityException (ignored): ${se.message}")
            } catch (_: Exception) {}

            val sock = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (se: SecurityException) {
                log("connectTo: create socket SecurityException: ${se.message}")
                null
            } catch (e: Exception) {
                log("connectTo: create socket failed: ${e.message}")
                null
            } ?: return@execute

            try {
                log("connectTo: socket.connect() ... (may block)")
                sock.connect()
                log("connectTo: connected")
                activeSocket = sock
                startIo(sock)
                try { uiCallback("CONNECTED", devAddr) } catch (_: Exception) {}
            } catch (se: SecurityException) {
                log("connectTo: SecurityException during connect: ${se.message}")
                try { sock.close() } catch (_: Exception) {}
            } catch (io: IOException) {
                log("connectTo: IOException during connect: ${io.message}")
                try { sock.close() } catch (_: Exception) {}
            } catch (t: Throwable) {
                log("connectTo: unexpected during connect: ${t.message}")
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startIo(sock: BluetoothSocket) {
        exec.execute {
            val input: InputStream? = try { sock.inputStream } catch (se: SecurityException) {
                log("startIo: inputStream SecurityException: ${se.message}"); null
            } catch (e: Exception) { log("startIo: inputStream error: ${e.message}"); null }

            val output: OutputStream? = try { sock.outputStream } catch (se: SecurityException) {
                log("startIo: outputStream SecurityException: ${se.message}"); null
            } catch (e: Exception) { log("startIo: outputStream error: ${e.message}"); null }

            if (input == null || output == null) {
                try { sock.close() } catch (_: Exception) {}
                return@execute
            }

            val buf = ByteArray(1024)
            try {
                while (true) {
                    val r = try { input.read(buf) } catch (io: IOException) {
                        log("startIo: read exception: ${io.message}")
                        -1
                    } catch (se: SecurityException) {
                        log("startIo: read SecurityException: ${se.message}")
                        -1
                    }
                    log("startIo: read returned $r")
                    if (r <= 0) break
                    val s = String(buf, 0, r, Charsets.UTF_8)
                    log("startIo: received -> $s")
                    try { uiCallback("RECV", s) } catch (_: Exception) {}
                }
            } finally {
                log("startIo: closing socket")
                try { sock.close() } catch (_: Exception) {}
                if (activeSocket === sock) activeSocket = null
                try { uiCallback("DISCONNECTED", "") } catch (_: Exception) {}
            }
        }
    }

    fun send(message: String) {
        exec.execute {
            val sock = activeSocket
            if (sock == null) {
                log("send: no active socket")
                return@execute
            }
            try {
                val out = try { sock.outputStream } catch (se: SecurityException) {
                    log("send: outputStream SecurityException: ${se.message}"); null
                } catch (e: Exception) {
                    log("send: outputStream error: ${e.message}"); null
                }
                if (out == null) {
                    log("send: no output stream available")
                    return@execute
                }
                val data = message.toByteArray(Charsets.UTF_8)
                out.write(data)
                out.flush()
                log("send: wrote ${data.size} bytes")
                try { uiCallback("SENT", message) } catch (_: Exception) {}
            } catch (se: SecurityException) {
                log("send: SecurityException: ${se.message}")
            } catch (io: IOException) {
                log("send: IOException: ${io.message}")
            } catch (t: Throwable) {
                log("send: unexpected: ${t.message}")
            }
        }
    }

    fun stopAll() {
        log("stopAll: closing everything")
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        stopServer()
        try { exec.shutdownNow() } catch (_: Exception) {}
    }

    private fun cleanupActiveSocket() {
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
    }
}
