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
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val adapter: BluetoothAdapter? by lazy {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter
    }

    private lateinit var btService: BluetoothService

    // UI state
    private var paired by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var selectedAddr by mutableStateOf<String?>(null)
    private var logs by mutableStateOf("")
    private var incomingText by mutableStateOf("")

    // connection & handshake mapping
    private var connectionEstablished by mutableStateOf(false)
    private var handshakeCompleted by mutableStateOf(false)
    private var pendingClaimId: String? = null // claim we sent and awaiting confirm

    // game mapping and state
    private var myId by mutableStateOf("")
    private var oppId by mutableStateOf("")
    private var player1Id by mutableStateOf("") // starter -> X
    private var player2Id by mutableStateOf("") // other -> O
    private var amPlayer1 by mutableStateOf<Boolean?>(null)
    private var boardState by mutableStateOf(Array(3) { Array(3) { " " } })
    private var turnCounter by mutableStateOf(0)
    private var allowLocalMoves by mutableStateOf(false)

    // permission launcher for S+ connect
    private val requestPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    @RequiresPermission("android.permission.LOCAL_MAC_ADDRESS")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btAdapter = adapter
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish(); return
        }

        myId = try { btAdapter.address } catch (_: SecurityException) { "unknown" }

        btService = BluetoothService(this, btAdapter)

        // collect logs & incoming messages
        lifecycleScope.launchWhenStarted {
            btService.logs.collectLatest { l -> logs += l + "\n" }
        }
        lifecycleScope.launchWhenStarted {
            btService.incoming.collectLatest { msg ->
                incomingText += msg + "\n"
                handleIncoming(msg)
            }
        }
        lifecycleScope.launchWhenStarted {
            btService.status.collectLatest { st -> connectionEstablished = (st == "CONNECTED") }
        }

        setContent { MaterialTheme { AppContent() } }
    }

    private fun refreshPaired() {
        val a = adapter ?: return
        try {
            val set = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // missing permission — show empty list
                listOf<BluetoothDevice>()
            } else {
                a.bondedDevices.toList()
            }
            paired = set.sortedBy { try { it.name ?: it.address } catch (_: Exception) { it.address } }
        } catch (se: SecurityException) {
            Toast.makeText(this, "Permission denied reading bonded devices", Toast.LENGTH_SHORT).show()
            paired = emptyList()
        }
    }

    private fun handleIncoming(raw: String) {
        val gm = GameMessage.fromJsonString(raw) ?: return
        // handshake: incoming claim (player1Choice set, player2Choice empty)
        val mg = gm.miniGame
        if (mg.player1Choice.isNotEmpty() && mg.player2Choice.isEmpty()) {
            // recipient: set mapping and reply with confirmation including choices ordered
            val claimed = mg.player1Choice
            // set oppId if unknown
            if (oppId.isBlank() && claimed != myId) oppId = claimed
            // set mapping: player1 = claimed, player2 = other
            player1Id = claimed
            player2Id = if (claimed == myId) oppId else myId
            amPlayer1 = (myId == player1Id)
            handshakeCompleted = true
            allowLocalMoves = (turnCounter % 2 == 0 && myId == player1Id)
            // send confirmation
            val confirm = GameMessage(
                gameState = GameState(Array(3) { Array(3) { " " } }, "0", " ", false, true, false),
                choices = listOf(Choice("player1", player1Id), Choice("player2", player2Id)),
                miniGame = MiniGame(player1Id, player1Id)
            )
            btService.sendJson(confirm.toJsonString())
            return
        }

        // confirmation: both set -> the sender receives this
        if (mg.player1Choice.isNotEmpty() && mg.player2Choice.isNotEmpty()) {
            // use choices ordering if available
            val p1 = gm.choices.getOrNull(0)?.name ?: mg.player1Choice
            val p2 = gm.choices.getOrNull(1)?.name ?: if (p1 == myId) oppId else myId
            player1Id = p1
            player2Id = p2
            amPlayer1 = (myId == player1Id)
            handshakeCompleted = true
            pendingClaimId = null
            // starter moves when turn == 0
            allowLocalMoves = (turnCounter % 2 == 0 && myId == player1Id)
            return
        }

        // normal game update: apply board, turn and mapping if present
        val p1id = gm.choices.getOrNull(0)?.name ?: ""
        val p2id = gm.choices.getOrNull(1)?.name ?: ""
        if (p1id.isNotEmpty() && p2id.isNotEmpty()) {
            player1Id = p1id; player2Id = p2id; amPlayer1 = (myId == player1Id)
        }
        boardState = copyBoard(gm.gameState.board)
        turnCounter = gm.gameState.turn.toIntOrNull() ?: turnCounter
        // enable local moves only if current player (by parity) equals myId
        val currentPlayer = if (turnCounter % 2 == 0) player1Id else player2Id
        allowLocalMoves = (currentPlayer == myId)
    }

    private fun copyBoard(src: Array<Array<String>>): Array<Array<String>> {
        val a = Array(3) { Array(3) { " " } }
        for (r in 0..2) for (c in 0..2) a[r][c] = src[r][c]
        return a
    }

    private fun computeWinner(board: Array<Array<String>>): String? {
        for (r in 0..2) if (board[r][0] != " " && board[r][0] == board[r][1] && board[r][1] == board[r][2]) return board[r][0]
        for (c in 0..2) if (board[0][c] != " " && board[0][c] == board[1][c] && board[1][c] == board[2][c]) return board[0][c]
        if (board[0][0] != " " && board[0][0] == board[1][1] && board[1][1] == board[2][2]) return board[0][0]
        if (board[0][2] != " " && board[0][2] == board[1][1] && board[1][1] == board[2][0]) return board[0][2]
        val full = board.all { row -> row.all { it != " " } }
        if (full) return null
        return null
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppContent() {
        var showWhoDialog by remember { mutableStateOf(false) }
        val verticalScrollState = rememberScrollState()
        val buttonRowScroll = rememberScrollState() // Still useful for smaller screens or future buttons

        Scaffold(topBar = { TopAppBar(title = { Text("TicTacToe - Responsive UI") }) }) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(12.dp)
                    .verticalScroll(verticalScrollState), // <-- page scrollable
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("MyId: $myId  OppId: ${oppId.ifBlank { "none" }}")
                Text("Connected: $connectionEstablished  Handshake: $handshakeCompleted  Your turn? ${if (allowLocalMoves) "YES" else "NO"}")

                // Button row: horizontally scrollable if too many buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(buttonRowScroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Critical buttons first:
                    Button(onClick = { btService.startServer(); logs += "server started\n" }) { Text("Start Server") }
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val ok = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                            if (!ok) {
                                requestPerms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                                return@Button
                            }
                        }
                        refreshPaired()
                    }) { Text("Show Paired") }
                    Button(onClick = {
                        val dev = paired.firstOrNull { it.address == selectedAddr }
                        if (dev != null) {
                            oppId = try { dev.address } catch (_: Exception) { oppId }
                            btService.connectTo(dev)
                        } else logs += "No device selected\n"
                    }) { Text("Connect") }

                    // **FIXED: Moved "Start Game" here to be immediately visible after Connect.**
                    Button(onClick = {
                        if (!connectionEstablished) Toast.makeText(this@MainActivity, "Not connected", Toast.LENGTH_SHORT).show()
                        else showWhoDialog = true
                    }) { Text("Start Game") }

                    // Less critical buttons last (can scroll):
                    Button(onClick = {
                        // Reset & Send as debug
                        boardState = Array(3) { Array(3) { " " } }
                        turnCounter = 0
                        allowLocalMoves = false
                        val gm = GameMessage(GameState(boardState, "0", " ", false, true, true), listOf(Choice("player1", myId), Choice("player2", oppId)), MiniGame("", ""))
                        btService.sendJson(gm.toJsonString())
                        logs += "Reset & sent\n"
                    }) { Text("Reset & Send") }
                }

                HorizontalDivider()

                // ... rest of AppContent remains the same
                Text("Paired devices (tap to select) — scroll if many:")
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(paired) { d ->
                        val name = try { d.name ?: "Unknown" } catch (_: Exception) { "Unknown" }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedAddr = d.address }
                                .padding(8.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name)
                                Text(d.address, style = MaterialTheme.typography.labelSmall)
                            }
                            if (selectedAddr == d.address) Text("SELECTED", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                HorizontalDivider()

                // Board area
                BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val totalPadding = 16.dp
                    val spacing = 8.dp
                    val boardAvailWidth: Dp = this.maxWidth - totalPadding
                    val cellSize: Dp = (boardAvailWidth - spacing * 2f) / 3f

                    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                        for (r in 0..2) {
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                                for (c in 0..2) {
                                    val cell = boardState[r][c]
                                    Card(
                                        modifier = Modifier
                                            .size(cellSize)
                                            .clickable(enabled = allowLocalMoves && cell == " ") {
                                                if (!handshakeCompleted) { logs += "Move blocked: handshake not complete\n"; return@clickable }
                                                val mySymbol = if (myId == player1Id) "X" else "O"
                                                boardState = copyBoard(boardState)
                                                boardState[r][c] = mySymbol
                                                turnCounter += 1
                                                val w = computeWinner(boardState)
                                                if (w != null) logs += "Local winner: $w\n"
                                                allowLocalMoves = false
                                                val gm = GameMessage(
                                                    gameState = GameState(boardState, turnCounter.toString(), " ", false, true, false),
                                                    choices = listOf(Choice("player1", player1Id), Choice("player2", player2Id)),
                                                    miniGame = MiniGame(player1Id, player1Id)
                                                )
                                                btService.sendJson(gm.toJsonString())
                                            },
                                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Text(cell, style = MaterialTheme.typography.titleLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }


                HorizontalDivider()

                Text("Logs:")
                Surface(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp).padding(4.dp)) { Text(logs) }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Incoming raw:")
                Surface(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp).padding(4.dp)) { Text(incomingText) }

                Spacer(modifier = Modifier.height(24.dp)) // small bottom padding when scrolled
            }
        }

        if (showWhoDialog) {
            AlertDialog(onDismissRequest = { showWhoDialog = false },
                title = { Text("Who Goes First?") },
                text = { Text("Tap ME if you want to go first (X), or OPPONENT to let the other device start.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingClaimId = myId
                        val gm = GameMessage(
                            GameState(Array(3) { Array(3) { " " } }, "0", " ", false, true, false),
                            listOf(Choice("player1", myId), Choice("player2", oppId)),
                            MiniGame(myId, "")
                        )
                        btService.sendJson(gm.toJsonString())
                        showWhoDialog = false
                        logs += "Sent claim: I go first (awaiting confirm)\n"
                    }) { Text("ME (I go first)") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        pendingClaimId = oppId
                        val gm = GameMessage(
                            GameState(Array(3) { Array(3) { " " } }, "0", " ", false, true, false),
                            listOf(Choice("player1", myId), Choice("player2", oppId)),
                            MiniGame(oppId, "")
                        )
                        btService.sendJson(gm.toJsonString())
                        showWhoDialog = false
                        logs += "Sent claim: Opponent goes first (awaiting confirm)\n"
                    }) { Text("OPPONENT (they go first)") }
                })
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        btService.stopAll()
    }
}
