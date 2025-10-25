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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

        val btAdapter = adapter
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        btService = BluetoothService(this, btAdapter)
        gameViewModel = GameViewModel(btAdapter, btService)

        lifecycleScope.launch {
            btService.incomingMessages.collectLatest { msg ->
                msg?.let { gameViewModel.handleIncomingMessage(it) }
            }
        }

        lifecycleScope.launch {
            btService.status.collectLatest { status ->
                if (status is ConnectionStatus.Connected) {
                    gameViewModel.setRole(status.isHost)
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
    private val adapter: BluetoothAdapter,
    private val btService: BluetoothService
) {
    private val myDeviceId: String = try {
        adapter.address
    } catch (e: SecurityException) {
        "DEVICE_${System.currentTimeMillis()}"
    }

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
    
    var gameMessage by mutableStateOf("")
        private set

    private var isHost = false
    private var amIPlayer1 = false

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            btService.status.collect { connectionStatus = it }
        }
    }

    fun setRole(isHostDevice: Boolean) {
        isHost = isHostDevice
    }

    fun loadPairedDevices() {
        try {
            pairedDevices = adapter.bondedDevices.toList()
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
        // Assign player IDs
        player1Id = if (iGoFirst) myDeviceId else (selectedDevice?.address ?: "OPPONENT")
        player2Id = if (iGoFirst) (selectedDevice?.address ?: "OPPONENT") else myDeviceId
        
        amIPlayer1 = (player1Id == myDeviceId)
        isMyTurn = amIPlayer1 // Player 1 always starts
        
        val msg = GameMessage(
            gameState = gameState,
            player1Id = player1Id,
            player2Id = player2Id,
            claimingPlayerId = player1Id
        )
        btService.sendMessage(msg.toJson())
    }

    fun makeMove(row: Int, col: Int) {
        if (!isMyTurn || gameState.board[row][col] != " ") return

        val newBoard = gameState.board.map { it.clone() }.toTypedArray()
        val mySymbol = if (amIPlayer1) "X" else "O"
        newBoard[row][col] = mySymbol

        val newTurn = gameState.turn + 1
        val winner = checkWinner(newBoard)
        val isDraw = winner == null && newBoard.all { row -> row.all { it != " " } }

        gameState = gameState.copy(
            board = newBoard,
            turn = newTurn,
            winner = winner ?: "",
            isDraw = isDraw
        )

        if (winner != null) {
            gameMessage = "Winner: ${if (winner == "X") "Player 1 (X)" else "Player 2 (O)"}"
        } else if (isDraw) {
            gameMessage = "Game is a draw!"
        }

        isMyTurn = false // Immediately disable my turn

        val msg = GameMessage(
            gameState = gameState,
            player1Id = player1Id,
            player2Id = player2Id
        )
        btService.sendMessage(msg.toJson())
    }

    fun handleIncomingMessage(json: String) {
        val msg = GameMessage.fromJson(json) ?: return

        // Initial role assignment from opponent
        if (player1Id.isEmpty() && msg.player1Id.isNotEmpty()) {
            player1Id = msg.player1Id
            player2Id = msg.player2Id
            amIPlayer1 = (myDeviceId == player1Id)
            
            // Only Player 1 has turn at start
            isMyTurn = amIPlayer1
            return
        }

        // Update game state from opponent's move
        val oldTurn = gameState.turn
        gameState = msg.gameState
        
        // Only update turn if board actually changed (opponent made a move)
        if (gameState.turn > oldTurn) {
            // Determine whose turn based on turn number
            // Turn 0, 2, 4... = Player 1
            // Turn 1, 3, 5... = Player 2
            val shouldBePlayer1Turn = (gameState.turn % 2 == 0)
            isMyTurn = (shouldBePlayer1Turn && amIPlayer1) || (!shouldBePlayer1Turn && !amIPlayer1)
            
            // Don't allow turns if game is over
            if (gameState.winner.isNotEmpty() || gameState.isDraw) {
                isMyTurn = false
            }
        }

        if (gameState.winner.isNotEmpty()) {
            gameMessage = "Winner: ${if (gameState.winner == "X") "Player 1 (X)" else "Player 2 (O)"}"
        } else if (gameState.isDraw) {
            gameMessage = "Game is a draw!"
        }

        if (gameState.isReset) {
            resetGame()
        }
    }

    fun resetGame() {
        gameState = GameState()
        gameMessage = ""
        isMyTurn = amIPlayer1 // Player 1 starts again
        
        val msg = GameMessage(
            gameState = gameState.copy(isReset = true),
            player1Id = player1Id,
            player2Id = player2Id
        )
        btService.sendMessage(msg.toJson())
    }

    private fun checkWinner(board: Array<Array<String>>): String? {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    viewModel: GameViewModel,
    onRequestPermissions: () -> Unit
) {
    var showTurnDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Tic-Tac-Toe Bluetooth") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                GameBoard(
                    gameState = viewModel.gameState,
                    isMyTurn = viewModel.isMyTurn,
                    onCellClick = { row, col -> viewModel.makeMove(row, col) }
                )

                if (viewModel.gameMessage.isNotEmpty()) {
                    Text(
                        text = viewModel.gameMessage,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = { viewModel.resetGame() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset Game")
                }
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
        Text(
            text = if (isMyTurn) "Your Turn" else "Opponent's Turn",
            style = MaterialTheme.typography.titleMedium,
            color = if (isMyTurn) Color(0xFF4CAF50) else Color(0xFFF44336)
        )
        
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
        text = { Text("Select who will make the first move (X)") },
        confirmButton = {
            TextButton(onClick = { onSelectTurn(true) }) {
                Text("ME")
            }
        },
        dismissButton = {
            TextButton(onClick = { onSelectTurn(false) }) {
                Text("OPPONENT")
            }
        }
    )
}
