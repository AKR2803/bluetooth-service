package com.example.kotlindemo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // cached adapter getter
    private val btAdapter: BluetoothAdapter? by lazy {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter
    }

    private lateinit var service: BluetoothService

    // Compose-observed state at activity level
    private var logs by mutableStateOf("")
    private var receivedText by mutableStateOf("")
    private var pairedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())

    // permission launcher for multiple perms (API31+)
    private val requestMultiplePerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // UI will refresh/reauthorize when user acts; we re-check when needed
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val adapter = btAdapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish(); return
        }

        service = BluetoothService(adapter) { tag, msg ->
            runOnUiThread {
                if (tag == "RECV") {
                    receivedText += msg + "\n"
                } else {
                    logs += msg + "\n"
                }
            }
        }

        // Request old-location permission on API29 and below for discovery if needed
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestMultiplePerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }

        setContent {
            MaterialTheme {
                BluetoothComposeScreen(
                    logs = logs,
                    received = receivedText,
                    paired = pairedDevices,
                    hasConnectPermission = { hasBtConnectPermission() },
                    hasScanPermission = { hasBtScanPermissionOrLegacy() },
                    requestPermissions = { requestPermissionsArray -> requestMultiplePerms.launch(requestPermissionsArray) },
                    onStartServer = { service.startServer() },
                    onStopServer = { service.stopServer() },
                    onRefreshPaired = { refreshPaired() },
                    onConnect = { device -> service.connectTo(device) },
                    onSend = { msg -> service.send(msg) }
                )
            }
        }

        // initial paired load
        refreshPaired()
    }

    // Helper: check BLUETOOTH_CONNECT on S+, otherwise true (API < S doesn't require it)
    private fun hasBtConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

    // For scanning: on S+ check BLUETOOTH_SCAN; on older check ACCESS_FINE_LOCATION
    private fun hasBtScanPermissionOrLegacy(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun refreshPaired() {
        val adapter = btAdapter ?: return
        val list = try {
            // On S+ we must check BLUETOOTH_CONNECT before reading bondedDevices or device.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtConnectPermission()) {
                // permission missing: do not read sensitive fields
                emptyList()
            } else {
                try {
                    adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
                } catch (se: SecurityException) {
                    runOnUiThread { Toast.makeText(this, "Permission error reading paired devices", Toast.LENGTH_SHORT).show() }
                    emptyList()
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
        pairedDevices = list
        runOnUiThread { Toast.makeText(this, "Paired: ${list.size}", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        service.stopAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothComposeScreen(
    logs: String,
    received: String,
    paired: List<BluetoothDevice>,
    hasConnectPermission: () -> Boolean,
    hasScanPermission: () -> Boolean,
    requestPermissions: (Array<String>) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRefreshPaired: () -> Unit,
    onConnect: (BluetoothDevice) -> Unit,
    onSend: (String) -> Unit
) {
    var message by remember { mutableStateOf("Hello from Compose!") }
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // helper: build an Array<String> of required perms depending on API level (lint-friendly)
    val neededPermsForRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("BT Compose Minimal (safe)") }) }) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onStartServer() }) { Text("Start Server") }
                Button(onClick = { onStopServer() }) { Text("Stop Server") }
                Button(onClick = {
                    // if missing perms, trigger request
                    if (!hasScanPermission()) {
                        requestPermissions(neededPermsForRequest)
                    } else {
                        onRefreshPaired()
                    }
                }) { Text("Refresh Paired") }
            }

            Text("Paired devices (tap to select):")
            LazyColumn(modifier = Modifier.height(140.dp)) {
                items(paired) { dev ->
                    // access name/address only if permission allows it (or API < S)
                    val canShow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasConnectPermission() else true
                    val name = try {
                        if (canShow) dev.name ?: "Unknown" else "Name hidden (grant permission)"
                    } catch (se: SecurityException) {
                        "Name hidden (permission)"
                    } catch (_: Exception) { "Unknown" }
                    val addr = try {
                        if (canShow) dev.address else "Address hidden (grant permission)"
                    } catch (se: SecurityException) {
                        "Address hidden (permission)"
                    } catch (_: Exception) { "no-addr" }

                    val selected = selectedAddress == addr
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .clickable { selectedAddress = addr }) {
                        Row(Modifier.padding(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("$name")
                                Text(addr, style = MaterialTheme.typography.labelSmall)
                            }
                            if (selected) Text("SELECTED", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val dev = paired.firstOrNull { it.address == selectedAddress }
                    if (dev != null) onConnect(dev)
                }) { Text("Connect") }

                Button(onClick = {
                    val dev = paired.firstOrNull { it.address == selectedAddress }
                    if (dev != null) coroutineScope.launch { onConnect(dev) }
                }) { Text("Connect (async)") }
            }

            OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSend(message) }, modifier = Modifier.fillMaxWidth()) { Text("Send") }

            Text("Received:")
            Surface(Modifier.fillMaxWidth().height(120.dp).padding(4.dp)) { Text(received) }

            Text("Logs:")
            Surface(Modifier.fillMaxWidth().height(140.dp).padding(4.dp).verticalScroll(
                rememberScrollState()
            )) { Text(logs) }
        }
    }
}