package com.example.kotlindemo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private lateinit var service: BluetoothService
    private lateinit var scope: CoroutineScope
    private lateinit var adapter: BluetoothAdapter

    private val permsSPlus = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )
    private val permsLegacy = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    // Single launcher reused for all (re)requests
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* UI will react to new permission state automatically */ }

    private val _discovered = kotlinx.coroutines.flow.MutableStateFlow<Map<String, android.bluetooth.BluetoothDevice>>(emptyMap())
    val discoveredFlow = _discovered // will expose to UI

    private var discoveryReceiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bm.adapter
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        // Inject permission check lambdas into the service
        service = BluetoothService(
            adapter = adapter,
            scope = scope,
            hasConnectPermission = {
                ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            },
            hasScanPermission = {
                ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            }
        )

        setContent {
            MaterialTheme {
                PermissionAwareBluetoothUI(
                    adapter = adapter,
                    service = service,
                    ensurePermissions = { ensurePermissions() },
                    shouldShowRationale = { p -> shouldShowRequestPermissionRationale(p) },
                    requestPermissions = { req -> requestPermissions.launch(req) },
                    startDiscovery = { startDiscovery() },
                    stopDiscovery = { stopDiscovery() },
                    discoveredFlow = discoveredFlow
                )
            }
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permsSPlus.forEach {
                if (ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                    needed += it
                }
            }
        } else {
            permsLegacy.forEach {
                if (ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                    needed += it
                }
            }
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        service.dispose()
    }

    // Call in onStart/onStop to follow Activity lifecycle
    override fun onStart() {
        super.onStart()
        registerDiscoveryReceiver()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        discoveryReceiver = null
        stopDiscovery()
    }

    private fun registerDiscoveryReceiver() {
        if (discoveryReceiver != null) return
        discoveryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    android.bluetooth.BluetoothDevice.ACTION_FOUND -> {
                        val device: android.bluetooth.BluetoothDevice? =
                            intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                        if (device != null) {
                            // Merge into map by address to avoid duplicates
                            _discovered.value = _discovered.value + (device.address to device)
                        }
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        // Optional: clear previous list at the start of a scan
                        _discovered.value = emptyMap()
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        // Scan complete
                    }
                }
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND)
        }
        registerReceiver(discoveryReceiver, f)
    }

    private fun startDiscovery() {
        // Permissions: Android 12+ needs BLUETOOTH_SCAN; pre-12 often also needs ACCESS_FINE_LOCATION.
        val canScan = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!canScan) {
            // Ask again using your existing launcher
            ensurePermissions()
            return
        }
        try {
            // Starting a new scan, hence cancel any in-progress first.
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (se: SecurityException) {
            // User denied permission (or OEM quirk) — surface to UI via log
            service.sendLine("") // no-op; keep
            // Better: push a line into your service log to trigger rationale if you kept that logic:
            // (or add a dedicated log function to service)
        }
    }

    private fun stopDiscovery() {
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (_: SecurityException) { /* ignore */ }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionAwareBluetoothUI(
    adapter: android.bluetooth.BluetoothAdapter,
    service: BluetoothService,
    ensurePermissions: () -> Unit,
    shouldShowRationale: (String) -> Boolean,
    requestPermissions: (Array<String>) -> Unit,
    startDiscovery: () -> Unit,
    stopDiscovery: () -> Unit,
    discoveredFlow: kotlinx.coroutines.flow.StateFlow<Map<String, android.bluetooth.BluetoothDevice>>
) {
    val status by service.status.collectAsState()
    val logLines by service.log.collectAsState()
    val discovered by discoveredFlow.collectAsState()

    // Show a one-shot rationale dialog when we detect SecurityException or missing perms
    var showRationale by remember { mutableStateOf(false) }

    // Detect SecurityException via logs and trigger rationale + re-request
    LaunchedEffect(logLines) {
        val last = logLines.lastOrNull() ?: return@LaunchedEffect
        if (last.contains("SecurityException", ignoreCase = true)) {
            showRationale = true
        }
    }

    // Also ensure we try to grab permissions on first launch
    LaunchedEffect(Unit) { ensurePermissions() }

    // Decide which set to request depending on API level
    val neededPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Optional: pre-check if we should show rationale (e.g., user denied once)
    val mustExplain = remember {
        neededPerms.any(shouldShowRationale)
    }

    if (showRationale || mustExplain) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Bluetooth permissions needed") },
            text = {
                Text(
                    "This app uses Bluetooth to connect two phones and exchange small messages " +
                            "for gameplay. We only use it while you are playing, and we do not access " +
                            "any personal data."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    requestPermissions(neededPerms)
                }) { Text("Grant") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("Not now") }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Bluetooth Demo — Connect 2 Devices") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Status: $status")

            // Server / Stop / Permissions
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { service.startListening() }) { Text("Listen (Server)") }
                Button(onClick = { service.stopAll() }) { Text("Stop") }
                Button(onClick = { ensurePermissions() }) { Text("Check Permissions") }
            }

            // --- New: Discovery controls ---
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { startDiscovery() }) { Text("Scan for Devices") }
                Button(onClick = { stopDiscovery() }) { Text("Stop Scan") }
            }

            // --- Discovered devices (tap to connect as client) ---
            Text("Discovered Devices (tap to connect):")
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(160.dp)) {
                val itemsList = discovered.values.sortedBy { it.name ?: it.address }
                items(itemsList, key = { it.address }) { dev ->
                    ElevatedCard(
                        onClick = { service.connect(dev) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(dev.name ?: "Unknown")
                            Text(dev.address, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // --- Still keep Paired list for quick connects ---
            val bonded = remember {
                mutableStateListOf(*adapter.bondedDevices.sortedBy {
                    it.name ?: it.address
                }.toTypedArray())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    bonded.clear()
                    bonded.addAll(adapter.bondedDevices)
                }) { Text("Refresh Paired") }
            }
            Text("Paired Devices (tap to connect):")
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                items(bonded, key = { it.address }) { dev ->
                    ElevatedCard(
                        onClick = { service.connect(dev) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(dev.name ?: "Unknown")
                            Text(dev.address, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // --- Send box + log (same as before) ---
            var outMsg by remember { mutableStateOf("""{"type":"ping"}""") }
            OutlinedTextField(
                value = outMsg,
                onValueChange = { outMsg = it },
                label = { Text("Message (newline-delimited)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { service.sendLine(outMsg) },
                enabled = status == BluetoothService.Status.CONNECTED,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send") }

            Text("Log:")
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(140.dp)) {
                items(logLines) { Text(it) }
            }
        }
    }

}