package com.example.asu_tic_tac_toe_app

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    navController: NavController,
    gameMode: String = "ai",
    difficulty: String? = null
) {
    // ========================================
    // GAME STATE - Using MutableList for proper state management
    // ========================================
    var board by remember { mutableStateOf(MutableList(3) { MutableList(3) { "" } }) }
    var currentPlayer by remember { mutableStateOf("X") }
    var gameStatus by remember { mutableStateOf("Player X's turn") }
    var isGameOver by remember { mutableStateOf(false) }
    var winner by remember { mutableStateOf<String?>(null) }
    var showGameOverDialog by remember { mutableStateOf(false) }

    val gameTitle = when (gameMode) {
        "ai" -> "vs AI (${difficulty?.uppercase()})"
        "local" -> "Local Multiplayer"
        "bluetooth" -> "Bluetooth Multiplayer"
        "wifidirect" -> "Wi-Fi Direct Multiplayer"
        else -> "Game"
    }

    // ========================================
    // GAME LOGIC FUNCTIONS
    // ========================================

    // Check for winner or draw
    fun checkGameStatus(): String? {
        Log.d("TicTacToe", "Checking game status...")
        Log.d("TicTacToe", "Board state: ${board.map { it.joinToString(",") }}")

        // Check rows
        for (row in 0..2) {
            if (board[row][0].isNotEmpty() &&
                board[row][0] == board[row][1] &&
                board[row][1] == board[row][2]) {
                Log.d("TicTacToe", "Winner found in row $row: ${board[row][0]}")
                return board[row][0]
            }
        }

        // Check columns
        for (col in 0..2) {
            if (board[0][col].isNotEmpty() &&
                board[0][col] == board[1][col] &&
                board[1][col] == board[2][col]) {
                Log.d("TicTacToe", "Winner found in column $col: ${board[0][col]}")
                return board[0][col]
            }
        }

        // Check diagonal (top-left to bottom-right)
        if (board[0][0].isNotEmpty() &&
            board[0][0] == board[1][1] &&
            board[1][1] == board[2][2]) {
            Log.d("TicTacToe", "Winner found in diagonal: ${board[0][0]}")
            return board[0][0]
        }

        // Check diagonal (top-right to bottom-left)
        if (board[0][2].isNotEmpty() &&
            board[0][2] == board[1][1] &&
            board[1][1] == board[2][0]) {
            Log.d("TicTacToe", "Winner found in anti-diagonal: ${board[0][2]}")
            return board[0][2]
        }

        // Check for draw
        var isBoardFull = true
        for (row in 0..2) {
            for (col in 0..2) {
                if (board[row][col].isEmpty()) {
                    isBoardFull = false
                    break
                }
            }
            if (!isBoardFull) break
        }

        if (isBoardFull) {
            Log.d("TicTacToe", "Board is full - Draw!")
            return "Draw"
        }

        Log.d("TicTacToe", "Game continues...")
        return null
    }

    // Handle game over state
    fun handleGameOver(result: String) {
        Log.d("TicTacToe", "handleGameOver called with result: $result")
        isGameOver = true
        winner = result
        showGameOverDialog = true

        gameStatus = when (result) {
            "Draw" -> "Game is a Draw!"
            else -> "Player $result Wins!"
        }

        Log.d("TicTacToe", "Game over state set. Dialog should show: $showGameOverDialog")
    }

    // Reset game function
    fun resetGame() {
        Log.d("TicTacToe", "Resetting game...")
        board = MutableList(3) { MutableList(3) { "" } }
        currentPlayer = "X"
        gameStatus = "Player X's turn"
        isGameOver = false
        winner = null
        showGameOverDialog = false
    }

    // ========================================
    // LOCAL MULTIPLAYER LOGIC
    // ========================================
    fun handleLocalGameplay(row: Int, col: Int) {
        Log.d("TicTacToe", "Cell clicked: ($row, $col)")
        Log.d("TicTacToe", "isGameOver: $isGameOver, Cell value: ${board[row][col]}")

        // Safety checks
        if (isGameOver) {
            Log.d("TicTacToe", "Game is over, ignoring click")
            return
        }

        if (board[row][col].isNotEmpty()) {
            Log.d("TicTacToe", "Cell occupied, ignoring click")
            return
        }

        // Update board - create new list to trigger recomposition
        val newBoard = MutableList(3) { r ->
            MutableList(3) { c ->
                board[r][c]
            }
        }
        newBoard[row][col] = currentPlayer
        board = newBoard

        Log.d("TicTacToe", "Board updated. Current player: $currentPlayer")

        // Check for winner
        val result = checkGameStatus()
        Log.d("TicTacToe", "Check result: $result")

        if (result != null) {
            Log.d("TicTacToe", "Calling handleGameOver...")
            handleGameOver(result)
            return
        }

        // Switch player
        currentPlayer = if (currentPlayer == "X") "O" else "X"
        gameStatus = "Player $currentPlayer's turn"
        Log.d("TicTacToe", "Switched to player: $currentPlayer")
    }

    // ========================================
    // AI GAMEPLAY LOGIC (PLACEHOLDER)
    // ========================================
    fun handleAIGameplay(row: Int, col: Int) {
        if (isGameOver || board[row][col].isNotEmpty()) return

        val newBoard = MutableList(3) { r -> MutableList(3) { c -> board[r][c] } }
        newBoard[row][col] = currentPlayer
        board = newBoard

        val result = checkGameStatus()
        if (result != null) {
            handleGameOver(result)
            return
        }

        currentPlayer = "X"
        gameStatus = "Your turn (AI not implemented)"
    }

    // ========================================
    // BLUETOOTH GAMEPLAY LOGIC (PLACEHOLDER)
    // ========================================
    fun handleBluetoothGameplay(row: Int, col: Int) {
        if (isGameOver || board[row][col].isNotEmpty()) return

        val newBoard = MutableList(3) { r -> MutableList(3) { c -> board[r][c] } }
        newBoard[row][col] = currentPlayer
        board = newBoard

        val result = checkGameStatus()
        if (result != null) {
            handleGameOver(result)
            return
        }

        currentPlayer = if (currentPlayer == "X") "O" else "X"
        gameStatus = "Waiting for opponent..."
    }

    // ========================================
    // WI-FI DIRECT GAMEPLAY LOGIC (PLACEHOLDER)
    // ========================================
    fun handleWifidirectGameplay(row: Int, col: Int) {
        if (isGameOver || board[row][col].isNotEmpty()) return

        val newBoard = MutableList(3) { r -> MutableList(3) { c -> board[r][c] } }
        newBoard[row][col] = currentPlayer
        board = newBoard

        val result = checkGameStatus()
        if (result != null) {
            handleGameOver(result)
            return
        }

        currentPlayer = if (currentPlayer == "X") "O" else "X"
        gameStatus = "Waiting for opponent..."
    }

    // ========================================
    // CELL CLICK HANDLER
    // ========================================
    fun onCellClick(row: Int, col: Int) {
        when (gameMode) {
            "local" -> handleLocalGameplay(row, col)
            "ai" -> handleAIGameplay(row, col)
            "bluetooth" -> handleBluetoothGameplay(row, col)
            "wifidirect" -> handleWifidirectGameplay(row, col)
        }
    }

    // Debug: Log whenever showGameOverDialog changes
    LaunchedEffect(showGameOverDialog) {
        Log.d("TicTacToe", "showGameOverDialog changed to: $showGameOverDialog")
    }

    // ========================================
    // GAME OVER DIALOG
    // ========================================
    if (showGameOverDialog) {
        Log.d("TicTacToe", "Rendering AlertDialog with winner: $winner")
        AlertDialog(
            onDismissRequest = {
                Log.d("TicTacToe", "Dialog dismiss requested")
            },
            title = {
                Text(
                    text = when (winner) {
                        "Draw" -> "🤝 It's a Draw!"
                        "X" -> "🎉 Player X Wins!"
                        "O" -> "🎉 Player O Wins!"
                        else -> "Game Over"
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when (winner) {
                            "Draw" -> "The game ended in a tie"
                            else -> "Congratulations!"
                        },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Game has been saved ✓",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { resetGame() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Play Again")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back to Home")
                }
            }
        )
    }

    // ========================================
    // UI RENDERING
    // ========================================
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(gameTitle) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Status Text
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isGameOver) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (isGameOver) 4.dp else 0.dp
                )
            ) {
                Text(
                    text = gameStatus,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = if (isGameOver) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Game Board
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (row in 0..2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (col in 0..2) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(
                                        if (isGameOver) MaterialTheme.colorScheme.surfaceVariant
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .border(3.dp, MaterialTheme.colorScheme.outline)
                                    .clickable(enabled = !isGameOver) {
                                        onCellClick(row, col)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = board[row][col],
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (board[row][col]) {
                                        "X" -> MaterialTheme.colorScheme.primary
                                        "O" -> MaterialTheme.colorScheme.secondary
                                        else -> Color.Transparent
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Control Buttons
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { resetGame() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Reset Game", fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Debug info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = when (gameMode) {
                                "ai" -> "Playing against AI - $difficulty mode"
                                "local" -> "Two players on same device"
                                "bluetooth" -> "Connected via Bluetooth"
                                "wifidirect" -> "Connected via Wi-Fi Direct"
                                else -> "Game in progress"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Debug: GameOver=$isGameOver, Dialog=$showGameOverDialog",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GameScreenPreview() {
    MaterialTheme {
        GameScreen(rememberNavController(), "local")
    }
}
