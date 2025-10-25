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
    private var pairedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
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

    // permission launcher
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

        // collectingn logs and incoming messages
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
        // if adapter is null simply return
        val a = adapter ?: return
        try {
            val set = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // if permissions are missing, display empty list
                listOf<BluetoothDevice>()
            } else {
                // otherwise, get the list of pairedDevices Bluetooth devices
                a.bondedDevices.toList()
            }
            // sort by name, and if name is null or something else fails, sort by address (as in the catch block)
            pairedDevices = set.sortedBy { try { it.name ?: it.address } catch (_: Exception) { it.address } }
        } 
        // if permission missing/denied display a toast 
        catch (se: SecurityException) {
            Toast.makeText(this, "Permission denied reading bonded devices", Toast.LENGTH_SHORT).show()
            pairedDevices = emptyList()
        }
    }

    // 🐛 bug in this method probably how it handles the ids for player1 and player2
    private fun handleIncoming(raw: String) {
        val gm = GameMessage.fromJsonString(raw) ?: return
        val mg = gm.miniGame

        if (mg.player1Choice.isNotEmpty() && mg.player2Choice.isEmpty()) {
            val claimed = mg.player1Choice

            // ensuring `oppId` is set correctly as the other player(i.e. the opponent)
            player1Id = claimed
            player2Id = if (claimed == myId) oppId else myId

            // important: ensure `oppId` is updated for future use if it was blank
            // IMPORTANT ⚠️⚠️⚠️
            if (oppId.isBlank()) {
                oppId = if (myId == player1Id) player2Id else player1Id
            }

            amPlayer1 = (myId == player1Id)
            handshakeCompleted = true
            allowLocalMoves = (turnCounter % 2 == 0 && myId == player1Id)

            // send confirmation
            val confirm = GameMessage(
                gameState = GameState(
                    board= Array(3) { Array(3) { " " } },
                    turn="0",
                    winner=" ",
                    draw=false,
                    connectionEstablished= true,
                    reset = false
                ),
                choices = listOf(
                    Choice("player1", player1Id),
                    Choice("player2", player2Id)
                ),
                miniGame = MiniGame(player1Id, player2Id)
            )
            btService.sendJson(confirm.toJsonString())
            return
        }

        // --- CONFIRMATION: BOTH SET (Sender's side) ---
        if (mg.player1Choice.isNotEmpty() && mg.player2Choice.isNotEmpty()) {
            // use MiniGame fields as they were set by the recipient
            player1Id = mg.player1Choice
            player2Id = mg.player2Choice

            amPlayer1 = (myId == player1Id)

            // IMPORTANT ⚠️⚠️⚠️: Set oppId based on confirmed roles
            oppId = if (myId == player1Id) player2Id else player1Id

            handshakeCompleted = true
            pendingClaimId = null

            // starter moves when turn == 0
            allowLocalMoves = (turnCounter % 2 == 0 && myId == player1Id)
            return
        }

        // using the `name` field for the ID
        val p1id = gm.choices.getOrNull(0)?.name ?: ""
        val p2id = gm.choices.getOrNull(1)?.name ?: ""

        if (p1id.isNotEmpty() && p2id.isNotEmpty()) {
            player1Id = p1id
            player2Id = p2id
            amPlayer1 = (myId == player1Id)
        }

        boardState = copyBoard(gm.gameState.board)
        turnCounter = gm.gameState.turn.toIntOrNull() ?: turnCounter

        // Final Turn Check: enable local moves only if current player (by parity) equals myId
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

        val screenScroll = rememberScrollState()
        val buttonRowScroll = rememberScrollState()
        val logsScroll = rememberScrollState()
        val incomingScroll = rememberScrollState()

        Scaffold(topBar = { TopAppBar(title = { Text("TicTacToe - Responsive UI") }) }) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .padding(12.dp)
                    .verticalScroll(screenScroll),
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
                        val dev = pairedDevices.firstOrNull { it.address == selectedAddr }
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
                LazyColumn(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)) {
                    items(pairedDevices) { d ->
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
                                                if (!handshakeCompleted) {
                                                    logs += "Move blocked: handshake not complete\n"; return@clickable
                                                }
                                                val mySymbol = if (myId == player1Id) "X" else "O"
                                                boardState = copyBoard(boardState)
                                                boardState[r][c] = mySymbol
                                                turnCounter += 1
                                                val w = computeWinner(boardState)
                                                if (w != null) logs += "Local winner: $w\n"
                                                allowLocalMoves = false
                                                val gm = GameMessage(
                                                    gameState = GameState(
                                                        boardState,
                                                        turnCounter.toString(),
                                                        " ",
                                                        false,
                                                        true,
                                                        false
                                                    ),
                                                    choices = listOf(
                                                        Choice("player1", player1Id),
                                                        Choice("player2", player2Id)
                                                    ),
                                                    miniGame = MiniGame(
                                                        "",
                                                        ""
                                                    ) // <-- FIX 5: miniGame is empty for normal move
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
                Surface(Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 200.dp)
                    .padding(4.dp)) { Text(logs, modifier = Modifier.verticalScroll(logsScroll)) }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Incoming raw:")
                Surface(Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 200.dp)
                    .padding(4.dp)) { Text(incomingText, modifier = Modifier.verticalScroll(incomingScroll)) }

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
                            // FIX 2: Correctly list choices (myId is Player 1/X)
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
                            // FIX 3: Correctly list choices (oppId is Player 1/X)
                            listOf(Choice("player1", oppId), Choice("player2", myId)),
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