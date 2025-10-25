package com.example.kotlindemo

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.*

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

fun BluetoothSocket?.closeSafe() {
    try { this?.close() } catch (_: Exception) {}
}

class BluetoothService(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // expose simple flows for UI
    val status = MutableStateFlow("IDLE") // IDLE, LISTENING, CONNECTING, CONNECTED, DISCONNECTED
    val incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs = MutableSharedFlow<String>(extraBufferCapacity = 128)

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null

    fun startServer() {
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    logs.tryEmit("Missing BLUETOOTH_CONNECT to startServer()")
                    return@launch
                }
                logs.tryEmit("startServer: listening")
                status.value = "LISTENING"
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("KotlinDemo", SPP_UUID)
                val sock = serverSocket?.accept() // blocks
                if (sock != null) {
                    onConnected(sock)
                } else {
                    logs.tryEmit("startServer: accept returned null")
                }
            } catch (se: SecurityException) {
                logs.tryEmit("startServer SecurityException: ${se.message}")
            } catch (io: Exception) {
                logs.tryEmit("startServer Exception: ${io.message}")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }
    }

    fun connectTo(device: BluetoothDevice) {
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    logs.tryEmit("Missing BLUETOOTH_CONNECT to connectTo()")
                    return@launch
                }
                status.value = "CONNECTING"
                logs.tryEmit("connectTo: connecting to ${device.address}")
                try { adapter.cancelDiscovery() } catch (_: Exception) {}
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect() // may block
                onConnected(sock)
            } catch (se: SecurityException) {
                logs.tryEmit("connectTo SecurityException: ${se.message}")
            } catch (io: Exception) {
                logs.tryEmit("connectTo failed: ${io.message}")
                status.value = "DISCONNECTED"
            }
        }
    }

    private fun onConnected(s: BluetoothSocket) {
        socket?.closeSafe()
        socket = s
        logs.tryEmit("onConnected: ${s.remoteDevice.address}")
        status.value = "CONNECTED"
        startIoLoop(s)
    }

    private fun startIoLoop(sock: BluetoothSocket) {
        scope.launch {
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val writer = OutputStreamWriter(sock.outputStream, Charsets.UTF_8)
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    incoming.tryEmit(line)
                }
            } catch (io: Exception) {
                logs.tryEmit("IO loop exception: ${io.message}")
            } finally {
                logs.tryEmit("Connection closed")
                status.value = "DISCONNECTED"
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    fun sendJson(json: String) {
        scope.launch {
            val s = socket
            if (s == null) {
                logs.tryEmit("sendJson failed: no socket")
                return@launch
            }
            try {
                val out = s.outputStream
                out.write((json + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                logs.tryEmit("sendJson: wrote ${json.length} bytes")
            } catch (io: Exception) {
                logs.tryEmit("sendJson exception: ${io.message}")
                try { s.close() } catch (_: Exception) {}
                status.value = "DISCONNECTED"
            }
        }
    }

    fun stopAll() {
        scope.launch {
            try { socket?.closeSafe() } catch (_: Exception) {}
            try { serverSocket?.close() } catch (_: Exception) {}
            socket = null
            serverSocket = null
            status.value = "DISCONNECTED"
            logs.tryEmit("stopAll: stopped")
        }
    }
}
