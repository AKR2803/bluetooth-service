package com.example.kotlindemo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var btService: BluetoothService
    private lateinit var gameViewModel: GameViewModel
    
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Handle result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btAdapter = adapter ?: run {
            finish()
            return
        }

        btService = BluetoothService(this, btAdapter)
        gameViewModel = GameViewModel(btService)

        lifecycleScope.launch {
            btService.incomingMessages.collectLatest { msg ->
                msg?.let { gameViewModel.handleIncomingMessage(it) }
            }
        }

        lifecycleScope.launch {
            btService.status.collectLatest { status ->
                when (status) {
                    is ConnectionStatus.Connected -> {
                        gameViewModel.setConnection(status.isHost, status.remoteAddress)
                    }
                    else -> {}
                }
            }
        }

        setContent {
            MaterialTheme {
                GameScreen(
                    viewModel = gameViewModel,
                    onRequestPermissions = { requestBluetoothPermissions() }
                )
            }
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btService.cleanup()
    }
}

class GameViewModel(
    private val btService: BluetoothService
) {
    val myDeviceId: String = UUID.randomUUID().toString().take(8)

    var pairedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
        private set
    
    var selectedDevice by mutableStateOf<BluetoothDevice?>(null)
        private set
    
    var connectionStatus by mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle)
        private set
    
    var gameState by mutableStateOf(GameState())
        private set
    
    var player1Id by mutableStateOf("")
        private set
    
    var player2Id by mutableStateOf("")
        private set
    
    var isMyTurn by mutableStateOf(false)
        private set
    
    var winnerSymbol by mutableStateOf("")
        private set
    
    var showGameOver by mutableStateOf(false)
        private set
    
    private var isHost = false
    private var opponentId = ""

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            btService.status.collect { connectionStatus = it }
        }
    }

    fun setConnection(isHostDevice: Boolean, remoteAddress: String) {
        isHost = isHostDevice
        opponentId = remoteAddress.takeLast(8)
    }

    fun loadPairedDevices() {
        try {
            pairedDevices = btService.adapter.bondedDevices.toList()
        } catch (e: SecurityException) {
            pairedDevices = emptyList()
        }
    }

    fun selectDevice(device: BluetoothDevice) {
        selectedDevice = device
    }

    fun startServer() {
        btService.startServer()
    }

    fun connectToSelected() {
        selectedDevice?.let { btService.connectToDevice(it) }
    }

    fun claimFirstTurn(iGoFirst: Boolean) {
        if (opponentId.isEmpty()) return
        
        if (iGoFirst) {
            player1Id = myDeviceId
            player2Id = "WAITING_FOR_OPPONENT_ID"
            isMyTurn = true
        } else {
            player1Id = "WAITING_FOR_OPPONENT_ID"
            player2Id = myDeviceId
            isMyTurn = false
        }
        
        val msg = GameMessage(
            gameState = gameState,
            player1Id = if (iGoFirst) myDeviceId else "OPPONENT_SLOT",
            player2Id = if (iGoFirst) "OPPONENT_SLOT" else myDeviceId,
            claimingPlayerId = myDeviceId
        )
        btService.sendMessage(msg.toJson())
    }

    fun makeMove(row: Int, col: Int) {
        if (!isMyTurn || gameState.board[row][col] != " ") return

        val amIPlayer1 = (myDeviceId == player1Id)
        val mySymbol = if (amIPlayer1) "X" else "O"
        val newBoard = gameState.board.map { it.clone() }.toTypedArray()
        newBoard[row][col] = mySymbol

        val loserSymbol = checkLoser(newBoard)
        val isDraw = loserSymbol == null && newBoard.all { row -> row.all { it != " " } }

        gameState = gameState.copy(
            board = newBoard,
            turn = gameState.turn + 1,
            winner = if (loserSymbol != null) (if (loserSymbol == "X") "O" else "X") else "",
            isDraw = isDraw
        )

        isMyTurn = false

        if (loserSymbol != null) {
            winnerSymbol = if (loserSymbol == "X") "O" else "X"
            showGameOver = true
        } else if (isDraw) {
            winnerSymbol = ""
            showGameOver = true
        }

        val msg = GameMessage(
            gameState = gameState,
            player1Id = player1Id,
            player2Id = player2Id
        )
        btService.sendMessage(msg.toJson())
    }

    fun handleIncomingMessage(json: String) {
        val msg = GameMessage.fromJson(json) ?: return

        // Handle reset first
        if (msg.gameState.isReset) {
            player1Id = ""
            player2Id = ""
            gameState = GameState()
            winnerSymbol = ""
            showGameOver = false
            isMyTurn = false
            return
        }

        // Initial role assignment - exchange device IDs
        if (player1Id.isEmpty() && msg.claimingPlayerId != "INIT") {
            val senderDeviceId = msg.claimingPlayerId
            
            if (msg.player1Id == "OPPONENT_SLOT") {
                // Sender wants to be Player 2, I become Player 1
                player1Id = myDeviceId
                player2Id = senderDeviceId
                isMyTurn = true
            } else {
                // Sender wants to be Player 1, I become Player 2  
                player1Id = senderDeviceId
                player2Id = myDeviceId
                isMyTurn = false
            }
            return
        }

        // Update game state from moves
        val oldTurn = gameState.turn
        gameState = msg.gameState
        
        if (gameState.turn > oldTurn) {
            val amIPlayer1 = (myDeviceId == player1Id)
            val shouldBePlayer1Turn = (gameState.turn % 2 == 0)
            isMyTurn = (shouldBePlayer1Turn && amIPlayer1) || (!shouldBePlayer1Turn && !amIPlayer1)
            
            if (gameState.winner.isNotEmpty() || gameState.isDraw) {
                isMyTurn = false
                winnerSymbol = gameState.winner
                showGameOver = true
            }
        }
    }

    fun resetGame() {
        // Clear roles completely on reset
        player1Id = ""
        player2Id = ""
        gameState = GameState()
        winnerSymbol = ""
        showGameOver = false
        isMyTurn = false
        
        val msg = GameMessage(
            gameState = GameState(isReset = true),
            player1Id = "",
            player2Id = ""
        )
        btService.sendMessage(msg.toJson())
    }

    private fun checkLoser(board: Array<Array<String>>): String? {
        for (row in board) {
            if (row[0] != " " && row[0] == row[1] && row[1] == row[2]) return row[0]
        }
        for (col in 0..2) {
            if (board[0][col] != " " && board[0][col] == board[1][col] && board[1][col] == board[2][col]) {
                return board[0][col]
            }
        }
        if (board[0][0] != " " && board[0][0] == board[1][1] && board[1][1] == board[2][2]) {
            return board[0][0]
        }
        if (board[0][2] != " " && board[0][2] == board[1][1] && board[1][1] == board[2][0]) {
            return board[0][2]
        }
        return null
    }
    
    fun getCurrentTurnPlayerId(): String {
        if (player1Id.isEmpty()) return ""
        val shouldBePlayer1Turn = (gameState.turn % 2 == 0)
        return if (shouldBePlayer1Turn) player1Id else player2Id
    }
    
    fun getWinnerName(): String {
        if (winnerSymbol.isEmpty()) return ""
        val winnerId = if (winnerSymbol == "X") player1Id else player2Id
        return if (winnerId == myDeviceId) "You" else "Opponent"
    }
    
    fun getOpponentId(): String = opponentId.ifEmpty { "Not Set" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    viewModel: GameViewModel,
    onRequestPermissions: () -> Unit
) {
    var showTurnDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Misère Tic-Tac-Toe") }) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2196F3))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "My ID: ${viewModel.myDeviceId}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Opponent ID: ${viewModel.getOpponentId()}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (viewModel.player1Id.isNotEmpty()) {
                            Text(
                                "Player 1 (X): ${viewModel.player1Id}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Player 2 (O): ${viewModel.player2Id}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                ConnectionStatusCard(viewModel.connectionStatus)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.startServer() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Host Game")
                    }
                    Button(
                        onClick = {
                            onRequestPermissions()
                            viewModel.loadPairedDevices()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Join Game")
                    }
                }

                if (viewModel.pairedDevices.isNotEmpty()) {
                    DeviceList(
                        devices = viewModel.pairedDevices,
                        selectedDevice = viewModel.selectedDevice,
                        onDeviceSelected = { viewModel.selectDevice(it) }
                    )
                    
                    Button(
                        onClick = { viewModel.connectToSelected() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.selectedDevice != null
                    ) {
                        Text("Connect")
                    }
                }

                if (viewModel.connectionStatus is ConnectionStatus.Connected) {
                    Button(
                        onClick = { showTurnDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Game")
                    }
                }

                if (viewModel.player1Id.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                    ) {
                        Text(
                            "⚠️ Misère Rules: Don't make 3 in a row! The player who completes a line LOSES!",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE65100)
                        )
                    }
                    
                    GameBoard(
                        gameState = viewModel.gameState,
                        isMyTurn = viewModel.isMyTurn,
                        onCellClick = { row, col -> viewModel.makeMove(row, col) }
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (viewModel.isMyTurn) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Current Turn: ${viewModel.getCurrentTurnPlayerId()}",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (viewModel.isMyTurn) "YOUR TURN" else "OPPONENT'S TURN",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.resetGame() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Game")
                    }
                }
            }

            if (viewModel.showGameOver) {
                GameOverDialog(
                    isDraw = viewModel.gameState.isDraw,
                    winnerName = viewModel.getWinnerName(),
                    winnerSymbol = viewModel.winnerSymbol,
                    onDismiss = { viewModel.resetGame() }
                )
            }
        }
    }

    if (showTurnDialog) {
        TurnSelectionDialog(
            onDismiss = { showTurnDialog = false },
            onSelectTurn = { iGoFirst ->
                viewModel.claimFirstTurn(iGoFirst)
                showTurnDialog = false
            }
        )
    }
}

@Composable
fun GameOverDialog(
    isDraw: Boolean,
    winnerName: String,
    winnerSymbol: String,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDraw) Color(0xFFFF9800) else Color(0xFF4CAF50)
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isDraw) "🤝" else "🎉",
                    fontSize = 72.sp
                )
                
                Text(
                    text = if (isDraw) "It's a Draw!" else "Game Over!",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                
                if (!isDraw) {
                    Text(
                        text = "$winnerName Win!",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    
                    Text(
                        text = "Symbol: $winnerSymbol",
                        fontSize = 24.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    
                    Text(
                        text = if (winnerName == "You") 
                            "Congratulations! You avoided making 3 in a row!" 
                        else 
                            "Opponent won! They forced you to make 3 in a row!",
                        fontSize = 16.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = if (isDraw) Color(0xFFFF9800) else Color(0xFF4CAF50)
                    )
                ) {
                    Text("Play Again", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(status: ConnectionStatus) {
    val (text, color) = when (status) {
        is ConnectionStatus.Connected -> {
            val role = if (status.isHost) "Host" else "Client"
            "Connected ($role)" to Color(0xFF4CAF50)
        }
        is ConnectionStatus.Connecting -> "Connecting..." to Color(0xFFFFC107)
        is ConnectionStatus.Listening -> "Waiting for connection..." to Color(0xFFFFC107)
        is ConnectionStatus.Disconnected -> "Disconnected" to Color(0xFFF44336)
        else -> "Idle" to Color(0xFFE0E0E0)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
    }
}

@Composable
fun DeviceList(
    devices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    onDeviceSelected: (BluetoothDevice) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
        ) {
            items(devices) { device ->
                val name = try { device.name ?: "Unknown" } catch (e: Exception) { "Unknown" }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeviceSelected(device) }
                        .background(if (selectedDevice == device) Color(0xFFE3F2FD) else Color.Transparent)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                    }
                    if (selectedDevice == device) {
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun GameBoard(
    gameState: GameState,
    isMyTurn: Boolean,
    onCellClick: (Int, Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..2) {
                    GameCell(
                        value = gameState.board[row][col],
                        enabled = isMyTurn && gameState.board[row][col] == " ",
                        onClick = { onCellClick(row, col) }
                    )
                }
            }
        }
    }
}

@Composable
fun GameCell(
    value: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .size(80.dp)
            .clickable(enabled = enabled, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.displayMedium,
                color = if (value == "X") Color(0xFF2196F3) else Color(0xFFF44336)
            )
        }
    }
}

@Composable
fun TurnSelectionDialog(
    onDismiss: () -> Unit,
    onSelectTurn: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who Goes First?") },
        text = { Text("Choose who will play as X (goes first)") },
        confirmButton = {
            TextButton(onClick = { onSelectTurn(true) }) {
                Text("ME (I go first)")
            }
        },
        dismissButton = {
            TextButton(onClick = { onSelectTurn(false) }) {
                Text("OPPONENT (They go first)")
            }
        }
    )
}