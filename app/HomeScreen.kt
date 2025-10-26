package com.example.asu_tic_tac_toe_app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController

@Composable
fun HomeScreen(navController: NavController) {
    var showDifficultyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Misere Tic-Tac-Toe",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        // Play with AI Button
        Button(
            onClick = { showDifficultyDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text("Play with AI", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Peer-to-Peer Same Device
        Button(
            onClick = { navController.navigate("game/local") },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text("Peer-to-Peer (Same Device)", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Peer-to-Peer Bluetooth
        Button(
            onClick = { navController.navigate("game/bluetooth") },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text("Peer-to-Peer (Bluetooth)", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Peer-to-Peer Wi-Fi Direct
        Button(
            onClick = { navController.navigate("game/wifidirect") },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text("Peer-to-Peer (Wi-Fi Direct)", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Past Games
        OutlinedButton(
            onClick = { navController.navigate("past_games") },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text("Past Games", style = MaterialTheme.typography.titleMedium)
        }
    }

    // Difficulty Selection Dialog
    if (showDifficultyDialog) {
        DifficultyDialog(
            onDismiss = { showDifficultyDialog = false },
            onDifficultySelected = { difficulty ->
                showDifficultyDialog = false
                navController.navigate("game/ai/$difficulty")
            }
        )
    }
}

@Composable
fun DifficultyDialog(
    onDismiss: () -> Unit,
    onDifficultySelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Difficulty") },
        text = {
            Column {
                Button(
                    onClick = { onDifficultySelected("easy") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Easy - Random AI moves")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onDifficultySelected("medium") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Medium - 50% optimal AI")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onDifficultySelected("hard") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Hard - Always optimal AI")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(navController = rememberNavController())
    }
}
