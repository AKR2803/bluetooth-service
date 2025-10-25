package com.example.kotlindemo

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.*

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

sealed class ConnectionStatus {
    object Idle : ConnectionStatus()
    object Listening : ConnectionStatus()
    object Connecting : ConnectionStatus()
    object Connected : ConnectionStatus()
    object Disconnected : ConnectionStatus()
}

class BluetoothService(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status

    private val _incomingMessages = MutableStateFlow<String?>(null)
    val incomingMessages: StateFlow<String?> = _incomingMessages

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var readerJob: Job? = null

    fun startServer() {
        scope.launch {
            try {
                if (!hasConnectPermission()) return@launch
                
                _status.value = ConnectionStatus.Listening
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("TicTacToe", SPP_UUID)
                val socket = serverSocket?.accept()
                
                socket?.let { onConnected(it) }
            } catch (e: IOException) {
                _status.value = ConnectionStatus.Disconnected
            } finally {
                serverSocket?.close()
                serverSocket = null
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        scope.launch {
            try {
                if (!hasConnectPermission()) return@launch
                
                _status.value = ConnectionStatus.Connecting
                adapter.cancelDiscovery()
                
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                onConnected(socket)
            } catch (e: IOException) {
                _status.value = ConnectionStatus.Disconnected
            }
        }
    }

    private fun onConnected(socket: BluetoothSocket) {
        clientSocket?.close()
        clientSocket = socket
        _status.value = ConnectionStatus.Connected
        startListening(socket)
    }

    private fun startListening(socket: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                socket.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        _incomingMessages.value = line
                    }
                }
            } catch (e: IOException) {
                // Connection closed
            } finally {
                _status.value = ConnectionStatus.Disconnected
                socket.close()
            }
        }
    }

    fun sendMessage(json: String) {
        scope.launch {
            try {
                clientSocket?.outputStream?.let { out ->
                    out.write((json + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: IOException) {
                _status.value = ConnectionStatus.Disconnected
            }
        }
    }

    fun disconnect() {
        scope.launch {
            readerJob?.cancel()
            clientSocket?.close()
            serverSocket?.close()
            clientSocket = null
            serverSocket = null
            _status.value = ConnectionStatus.Disconnected
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}
