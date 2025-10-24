package com.example.kotlindemo

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID

class BluetoothService(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
    // Injected permission checks from Activity (so Service has no Context dependency)
    private val hasConnectPermission: () -> Boolean,
    private val hasScanPermission: () -> Boolean
) {
    companion object {
        val APP_UUID: UUID = UUID.fromString("c2a9b2ef-10ad-43e3-b8af-0ae6d7d1b1a7")
        private const val SERVICE_NAME = "TicTacToeBT"
        private const val TAG = "BluetoothService"
    }

    enum class Status { IDLE, LISTENING, CONNECTING, CONNECTED, ERROR }

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private fun log(msg: String) {
        _log.value = _log.value + msg
        Log.d(TAG, msg)
    }

    private fun safeDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "Unknown"
        return try {
            if (hasConnectPermission()) device.name ?: device.address else device.address
        } catch (se: SecurityException) {
            "Unknown (no BT_CONNECT)"
        }
    }

    fun startListening() {
        stopAll()
        _status.value = Status.LISTENING
        scope.launch(Dispatchers.IO) {
            try {
                // Creating/accepting a server socket can throw SecurityException on some OEM builds.
                val ss = try {
                    adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                } catch (se: SecurityException) {
                    log("SecurityException (listen): ${se.message}")
                    _status.value = Status.ERROR
                    return@launch
                }
                serverSocket = ss
                log("Listening…")

                val accepted: BluetoothSocket = try {
                    ss.accept() // Blocks
                } catch (se: SecurityException) {
                    log("SecurityException (accept): ${se.message}")
                    _status.value = Status.ERROR
                    return@launch
                } catch (io: IOException) {
                    log("Accept failed: ${io.message}")
                    _status.value = Status.ERROR
                    return@launch
                }

                log("Accepted connection from ${safeDeviceName(accepted.remoteDevice)}")
                onConnected(accepted)
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        stopAll()
        _status.value = Status.CONNECTING
        scope.launch(Dispatchers.IO) {
            // Check permissions up front for clearer errors
            if (!hasConnectPermission()) {
                log("Missing BLUETOOTH_CONNECT permission.")
                _status.value = Status.ERROR
                return@launch
            }

            val s: BluetoothSocket = try {
                device.createRfcommSocketToServiceRecord(APP_UUID)
            } catch (se: SecurityException) {
                log("SecurityException (createSocket): ${se.message}")
                _status.value = Status.ERROR
                return@launch
            } catch (io: IOException) {
                log("Socket create failed: ${io.message}")
                _status.value = Status.ERROR
                return@launch
            }

            // Discovery must be cancelled before connect; guard with try/catch + permission gate
            try {
                if (hasScanPermission()) {
                    adapter.cancelDiscovery()
                } else {
                    log("No BLUETOOTH_SCAN permission; skipping cancelDiscovery()")
                }
            } catch (se: SecurityException) {
                log("SecurityException (cancelDiscovery): ${se.message}")
            }

            try {
                log("Connecting to ${safeDeviceName(device)}…")
                s.connect()
                log("Connected to ${safeDeviceName(device)}")
                onConnected(s)
            } catch (se: SecurityException) {
                log("SecurityException (connect): ${se.message}")
                _status.value = Status.ERROR
                try { s.close() } catch (_: Exception) {}
            } catch (io: IOException) {
                log("Connect failed: ${io.message}")
                _status.value = Status.ERROR
                try { s.close() } catch (_: Exception) {}
            }
        }
    }

    private fun onConnected(s: BluetoothSocket) {
        socket = s
        input = try { s.inputStream } catch (se: SecurityException) {
            log("SecurityException (getInputStream): ${se.message}")
            null
        }
        output = try { s.outputStream } catch (se: SecurityException) {
            log("SecurityException (getOutputStream): ${se.message}")
            null
        }

        if (input == null || output == null) {
            _status.value = Status.ERROR
            stopAll()
            return
        }

        _status.value = Status.CONNECTED
        scope.launch(Dispatchers.IO) { readerLoop() }
    }

    private suspend fun readerLoop() {
        val buf = ByteArray(1024)
        val sb = StringBuilder()
        try {
            while (true) {
                val n = try {
                    input?.read(buf) ?: break
                } catch (se: SecurityException) {
                    log("SecurityException (read): ${se.message}")
                    break
                } catch (io: IOException) {
                    log("Read error: ${io.message}")
                    break
                }

                if (n <= 0) break
                val chunk = String(buf, 0, n, Charset.forName("UTF-8"))
                sb.append(chunk)
                var idx = sb.indexOf("\n")
                while (idx != -1) {
                    val line = sb.substring(0, idx).trim()
                    if (line.isNotEmpty()) log("RX: $line")
                    sb.delete(0, idx + 1)
                    idx = sb.indexOf("\n")
                }
            }
        } finally {
            _status.value = Status.ERROR
            stopAll()
        }
    }

    fun sendLine(line: String) {
        if (_status.value != Status.CONNECTED) {
            log("Not connected")
            return
        }
        try {
            val bytes = (line + "\n").toByteArray(Charset.forName("UTF-8"))
            output?.write(bytes)
            output?.flush()
            log("TX: $line")
        } catch (se: SecurityException) {
            log("SecurityException (write): ${se.message}")
            _status.value = Status.ERROR
            stopAll()
        } catch (io: IOException) {
            log("Write error: ${io.message}")
            _status.value = Status.ERROR
            stopAll()
        }
    }

    fun stopAll() {
        try { serverSocket?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        serverSocket = null; socket = null; input = null; output = null
        if (_status.value != Status.IDLE) _status.value = Status.IDLE
    }

    fun dispose() {
        stopAll()
        scope.cancel()
    }
}
