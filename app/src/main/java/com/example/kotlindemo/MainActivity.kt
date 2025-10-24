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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * MainActivity (Compose) — TicTacToe with Bluetooth
 *
 * Fixed handshake & turn logic:
 *  - Sender sends claim (miniGame.player1Choice set, player2Choice empty)
 *  - Recipient replies with confirmation (both set) and includes choices=[player1Id,player2Id]
 *  - Only after confirmation does either side enable moves based on turn counter.
 *  - Moves are sent as full-state JSON; recipient applies board and enables moves if turn matches them.
 */

class MainActivity : ComponentActivity() {

    private val btAdapter: BluetoothAdapter? by lazy {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter
    }

    private lateinit var service: BluetoothService

    // UI state
    private var logs by mutableStateOf("")
    private var receivedText by mutableStateOf("")
    private var pairedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())

    // device ids
    private var myId by mutableStateOf("")
    private var oppId by mutableStateOf("")

    // mapping: player1Id is starter (X), player2Id is other (O)
    private var player1Id by mutableStateOf("")
    private var player2Id by mutableStateOf("")

    // connection/handshake state
    private var connectionEstablished by mutableStateOf(false)
    private var handshakeCompleted by mutableStateOf(false)

    private var whoGoesFirstDialog by mutableStateOf(false)
    private var claimSent by mutableStateOf(false)
    private var pendingClaimId by mutableStateOf<String?>(null) // claim sender state waiting for confirmation

    // game state
    private var boardState by mutableStateOf(Array(3) { Array(3) { " " } })
    private var turnCounter by mutableStateOf(0)
    private var winnerId by mutableStateOf(" ")
    private var drawState by mutableStateOf(false)
    private var allowLocalMoves by mutableStateOf(false)
    private var amPlayer1 by mutableStateOf<Boolean?>(null) // true => this device == player1 (X)

    // permission launcher
    private val requestPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val adapter = btAdapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish(); return
        }

        // safe local MAC
        myId = try { adapter.address } catch (_: Exception) { "unknown" }

        service = BluetoothService(adapter) { tag, msg ->
            runOnUiThread {
                when (tag) {
                    "RECV" -> {
                        // parse incoming JSON
                        val gm = GameMessage.fromJsonString(msg)
                        if (gm != null) {
                            handleIncomingGameMessage(gm)
                        } else {
                            receivedText += msg + "\n"
                        }
                    }
                    "CONNECTED" -> {
                        oppId = msg.ifBlank { oppId }
                        connectionEstablished = true
                        logs += "CONNECTED to $msg\n"
                    }
                    "DISCONNECTED" -> {
                        connectionEstablished = false
                        handshakeCompleted = false
                        claimSent = false
                        allowLocalMoves = false
                        pendingClaimId = null
                        logs += "DISCONNECTED\n"
                    }
                    else -> logs += "$msg\n"
                }
            }
        }

        // legacy permission for bonded devices on Q and below
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }

        refreshPaired()

        setContent { MaterialTheme { TicTacToeScreen() } }
    }

    private fun refreshPaired() {
        val adapter = btAdapter ?: return
        val list = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                emptyList()
            } else {
                try {
                    adapter.bondedDevices.toList().sortedBy { try { it.name ?: it.address } catch (_: Exception) { it.address } }
                } catch (se: SecurityException) { emptyList() }
            }
        } catch (t: Throwable) { emptyList() }
        pairedDevices = list
    }

    private fun copyBoard(src: Array<Array<String>>): Array<Array<String>> {
        val a = Array(3) { Array(3) { " " } }
        for (r in 0..2) for (c in 0..2) a[r][c] = src[r][c]
        return a
    }

    private fun resetLocalGameState() {
        boardState = Array(3) { Array(3) { " " } }
        turnCounter = 0
        winnerId = " "
        drawState = false
        allowLocalMoves = false
        amPlayer1 = null
        handshakeCompleted = false
        claimSent = false
        pendingClaimId = null
        player1Id = ""
        player2Id = ""
    }

    // send a claim (miniGame.player1Choice = claimedId, player2Choice = "")
    private fun sendClaim(claimedId: String) {
        // include choices best-effort (myId first, oppId second) so recipient can map quickly
        val choices = listOf(Choice("player1", myId), Choice("player2", oppId))
        val gm = GameMessage(
            gameState = GameState(Array(3) { Array(3) { " " } }, "0", " ", false, true, false),
            choices = choices,
            miniGame = MiniGame(player1Choice = claimedId, player2Choice = "")
        )
        logs += "SENDING CLAIM: ${gm.toJsonString()}\n"
        service.send(gm.toJsonString())
        claimSent = true
        pendingClaimId = claimedId
        // do NOT enable moves yet — wait for confirmation
        allowLocalMoves = false
    }

    // recipient replies confirming the claimed starter and includes ordered choices [player1,player2]
    private fun sendConfirmAndSetMapping(claimedStarter: String) {
        // determine mapping deterministically:
        val p1 = claimedStarter
        val p2 = if (p1 == myId) oppId else myId
        player1Id = p1
        player2Id = p2
        amPlayer1 = (myId == player1Id)
        handshakeCompleted = true
        // starter should be player1; enable move only if I'm player1 (starter)
        allowLocalMoves = (myId == player1Id) && (turnCounter % 2 == 0)

        val gm = GameMessage(
            gameState = GameState(Array(3) { Array(3) { " " } }, "0", " ", false, true, false),
            choices = listOf(Choice("player1", player1Id), Choice("player2", player2Id)),
            miniGame = MiniGame(player1Choice = p1, player2Choice = p1)
        )
        logs += "SENDING CONFIRM: ${gm.toJsonString()}\n"
        service.send(gm.toJsonString())
    }

    // sender receives confirmation -> finalize mapping & enable if starter
    private fun processConfirmation(gm: GameMessage) {
        // rely on choices ordering if present
        val p1 = gm.choices.getOrNull(0)?.name ?: gm.miniGame.player1Choice
        val p2 = gm.choices.getOrNull(1)?.name ?: if (p1 == myId) oppId else myId
        player1Id = p1
        player2Id = p2
        amPlayer1 = (myId == player1Id)
        handshakeCompleted = true
        claimSent = false
        pendingClaimId = null
        // ensure turnCounter resets to 0 as per handshake
        turnCounter = 0
        // enable moves for starter only
        allowLocalMoves = (myId == player1Id)
        logs += "CONFIRMED: player1=$player1Id player2=$player2Id amPlayer1=$amPlayer1\n"
    }

    // send full game state (choices in order and miniGame both set to player1Id)
    private fun sendFullState() {
        if (player1Id.isBlank() || player2Id.isBlank()) {
            logs += "Can't send full state: mapping not complete\n"
            return
        }
        val gm = GameMessage(
            gameState = GameState(copyBoard(boardState), turnCounter.toString(), winnerId, drawState, connectionEstablished, false),
            choices = listOf(Choice("player1", player1Id), Choice("player2", player2Id)),
            miniGame = MiniGame(player1Choice = player1Id, player2Choice = player1Id)
        )
        logs += "SENDING STATE: ${gm.toJsonString()}\n"
        service.send(gm.toJsonString())
    }

    private fun handleIncomingGameMessage(gm: GameMessage) {
        val mg = gm.miniGame

        // 1) initial claim (player1Choice set, player2Choice empty)
        if (mg.player1Choice.isNotEmpty() && mg.player2Choice.isEmpty()) {
            val claimed = mg.player1Choice
            logs += "RECV CLAIM: $claimed\n"
            // ensure oppId captured
            if (oppId.isBlank() && claimed != myId) oppId = claimed
            // recipient should reply with confirm and set mapping
            sendConfirmAndSetMapping(claimed)
            return
        }

        // 2) confirmation: both set -> mapping present (sender receives this)
        if (mg.player1Choice.isNotEmpty() && mg.player2Choice.isNotEmpty()) {
            logs += "RECV CONFIRM (handshake)\n"
            // sender or recipient both can arrive here; unify processing
            processConfirmation(gm)
            return
        }

        // 3) regular game update (incoming board + turn)
        // update mapping if choices present
        val p1id = gm.choices.getOrNull(0)?.name ?: ""
        val p2id = gm.choices.getOrNull(1)?.name ?: ""
        if (p1id.isNotEmpty() && p2id.isNotEmpty()) {
            player1Id = p1id; player2Id = p2id; amPlayer1 = (myId == player1Id)
        }
        // apply board & turn
        boardState = copyBoard(gm.gameState.board)
        turnCounter = gm.gameState.turn.toIntOrNull() ?: turnCounter
        winnerId = gm.gameState.winner
        drawState = gm.gameState.draw
        connectionEstablished = gm.gameState.connectionEstablished
        logs += "RECV GAME UPDATE turn=$turnCounter winner=$winnerId\n"

        // determine current player (authoritative by turnCounter)
        val currentPlayerId = if (turnCounter % 2 == 0) player1Id else player2Id
        allowLocalMoves = (currentPlayerId == myId)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TicTacToeScreen() {
        var selectedDeviceAddr by remember { mutableStateOf<String?>(null) }

        Scaffold(topBar = { TopAppBar(title = { Text("Tic-Tac-Toe — Bluetooth") }) }) { padding ->
            Column(Modifier.padding(padding).padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MyId: $myId   OppId: ${if (oppId.isBlank()) "none" else oppId}")
                Text("Connected: $connectionEstablished   Handshake: $handshakeCompleted   Your turn? ${if (allowLocalMoves) "YES" else "NO"}")

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = {
                        service.startServer()
                        logs += "Server started\n"
                    }) { Text("Start Server") }
                    Button(modifier = Modifier.weight(1f), onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                requestPerms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
                            } else refreshPaired()
                        } else refreshPaired()
                    }) { Text("Refresh Paired") }
                    Button(modifier = Modifier.weight(1f), onClick = {
                        val dev = pairedDevices.firstOrNull { it.address == selectedDeviceAddr }
                        if (dev != null) {
                            service.connectTo(dev)
                            oppId = try { dev.address } catch (_: Exception) { oppId }
                        } else logs += "No device selected\n"
                    }) { Text("Connect") }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = {
                        if (!connectionEstablished) {
                            Toast.makeText(this@MainActivity, "Not connected — connect first", Toast.LENGTH_SHORT).show()
                        } else {
                            // show dialog to claim starter (Start Game)
                            // Clear pending handshake flags; send claim on choice
                            claimSent = false
                            pendingClaimId = null
                            whoGoesFirstDialog = true
                        }
                    }) { Text("Start Game") }

                    Button(modifier = Modifier.weight(1f), onClick = {
                        resetLocalGameState()
                        val gm = GameMessage(GameState(boardState, "0", " ", false, connectionEstablished, true),
                            listOf(Choice("player1", myId), Choice("player2", oppId)), MiniGame("", ""))
                        service.send(gm.toJsonString())
                        logs += "Reset & sent\n"
                    }) { Text("Reset & Send") }
                }

                Text("Paired devices (tap to select) — scroll if many:")
                LazyColumn(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                    items(pairedDevices) { dev ->
                        val canShow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                        else true
                        val name = try { if (canShow) dev.name ?: "Unknown" else "Name hidden" } catch (_: Exception) { "Unknown" }
                        val addr = try { if (canShow) dev.address else "Address hidden" } catch (_: Exception) { "no-addr" }
                        Row(Modifier.fillMaxWidth().clickable { selectedDeviceAddr = addr }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("$name"); Text(addr, style = MaterialTheme.typography.labelSmall) }
                            if (selectedDeviceAddr == addr) Text("SELECTED", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Board
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in 0..2) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in 0..2) {
                                val cell = boardState[r][c]
                                Card(modifier = Modifier.weight(1f).aspectRatio(1f).clickable(enabled = allowLocalMoves && cell == " ") {
                                    // local move (only allowed when allowLocalMoves true)
                                    if (!handshakeCompleted) {
                                        logs += "Attempted move but handshake not complete\n"
                                        return@clickable
                                    }
                                    // determine symbol for this device: player1->X, player2->O
                                    val mySymbol = if (myId == player1Id) "X" else "O"
                                    boardState = copyBoard(boardState)
                                    boardState[r][c] = mySymbol
                                    // increment turn (authoritative)
                                    turnCounter += 1
                                    // compute winner/draw locally
                                    val winnerSymbol = computeWinner(boardState)
                                    if (winnerSymbol != null) {
                                        winnerId = if (winnerSymbol == "X") player1Id else player2Id
                                        logs += "Winner set: $winnerId\n"
                                        allowLocalMoves = false
                                    } else {
                                        // disable local moves until peer sends the updated state back
                                        allowLocalMoves = false
                                    }
                                    // send full state immediately
                                    sendFullState()
                                }, elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text(cell, style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Logs:")
                Surface(Modifier.fillMaxWidth().height(120.dp).padding(4.dp)) { Text(logs) }
                Spacer(Modifier.height(4.dp))
                Text("Received raw:")
                Surface(Modifier.fillMaxWidth().height(120.dp).padding(4.dp)) { Text(receivedText) }
            }
        }

        // Who Goes First dialog (local initiator only)
        if (whoGoesFirstDialog && connectionEstablished && !handshakeCompleted) {
            AlertDialog(onDismissRequest = { whoGoesFirstDialog = false }, title = { Text("Who Goes First?") },
                text = { Text("Tap ME if you want to go first (X), or OPPONENT to let the other device go first.") },
                confirmButton = {
                    TextButton(onClick = {
                        // ME tapped — send claim that myId is player1
                        // do not enable moves yet until confirmation; we will enable on confirmation
                        pendingClaimId = myId
                        sendClaim(myId)
                        whoGoesFirstDialog = false
                        logs += "Claimed ME as starter (waiting for confirm)\n"
                    }) { Text("ME (I go first)") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        // OPPONENT tapped — claim opponent is player1
                        pendingClaimId = oppId
                        sendClaim(oppId)
                        whoGoesFirstDialog = false
                        logs += "Claimed OPPONENT as starter (waiting for confirm)\n"
                    }) { Text("OPPONENT (they go first)") }
                })
        }
    }

    // returns "X", "O" or null
    private fun computeWinner(board: Array<Array<String>>): String? {
        for (r in 0..2) if (board[r][0] != " " && board[r][0] == board[r][1] && board[r][1] == board[r][2]) return board[r][0]
        for (c in 0..2) if (board[0][c] != " " && board[0][c] == board[1][c] && board[1][c] == board[2][c]) return board[0][c]
        if (board[0][0] != " " && board[0][0] == board[1][1] && board[1][1] == board[2][2]) return board[0][0]
        if (board[0][2] != " " && board[0][2] == board[1][1] && board[1][1] == board[2][0]) return board[0][2]
        val full = board.all { row -> row.all { it != " " } }
        if (full) { drawState = true; return null }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        service.stopAll()
    }
}
