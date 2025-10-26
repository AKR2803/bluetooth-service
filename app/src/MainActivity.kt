package com.example.asu_tic_tac_toe_app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.kotlindemo.GameScreen as BluetoothGameScreen
import com.example.kotlindemo.GameViewModel
import com.example.kotlindemo.BluetoothService
import android.bluetooth.BluetoothManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(navController)
                        }
                        composable("game/ai/{difficulty}") { backStackEntry ->
                            val difficulty = backStackEntry.arguments?.getString("difficulty")
                            GameScreen(navController, "ai", difficulty)
                        }
                        composable("game/local") {
                            GameScreen(navController, "local")
                        }
                        composable("game/bluetooth") {
                            val bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                            val bluetoothService = BluetoothService(this@MainActivity, bluetoothAdapter)
                            val gameViewModel = GameViewModel(bluetoothService)
                            
                            BluetoothGameScreen(
                                viewModel = gameViewModel,
                                onRequestPermissions = {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                        requestPermissions(
                                            arrayOf(
                                                android.Manifest.permission.BLUETOOTH_CONNECT,
                                                android.Manifest.permission.BLUETOOTH_SCAN
                                            ), 1
                                        )
                                    }
                                }
                            )
                        }
                        composable("game/wifidirect") {
                            GameScreen(navController, "wifidirect")
                        }
                        composable("past_games") {
                            PastGamesScreen(navController)
                        }
                    }
                }
            }
        }
    }
}
