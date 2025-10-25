package com.example.kotlindemo

import org.json.JSONArray
import org.json.JSONObject

data class GameState(
    val board: Array<Array<String>> = Array(3) { Array(3) { " " } },
    val turn: Int = 0,
    val winner: String = "",
    val isDraw: Boolean = false,
    val isReset: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameState) return false
        return board.contentDeepEquals(other.board) && turn == other.turn
    }

    override fun hashCode(): Int {
        return board.contentDeepHashCode() + turn
    }
}

data class GameMessage(
    val gameState: GameState,
    val player1Id: String = "",
    val player2Id: String = "",
    val claimingPlayerId: String = ""
) {
    fun toJson(): String {
        val root = JSONObject()
        
        // Game state
        val gs = JSONObject().apply {
            val boardArray = JSONArray()
            gameState.board.forEach { row ->
                val rowArray = JSONArray()
                row.forEach { cell -> rowArray.put(cell) }
                boardArray.put(rowArray)
            }
            put("board", boardArray)
            put("turn", gameState.turn.toString())
            put("winner", gameState.winner)
            put("draw", gameState.isDraw)
            put("connectionEstablished", true)
            put("reset", gameState.isReset)
        }
        root.put("gameState", gs)
        
        // Metadata
        val metadata = JSONObject().apply {
            val choices = JSONArray()
            if (player1Id.isNotEmpty()) {
                choices.put(JSONObject().apply {
                    put("id", "player1")
                    put("name", player1Id)
                })
            }
            if (player2Id.isNotEmpty()) {
                choices.put(JSONObject().apply {
                    put("id", "player2")
                    put("name", player2Id)
                })
            }
            put("choices", choices)
            
            val miniGame = JSONObject().apply {
                put("player1Choice", claimingPlayerId)
                put("player2Choice", if (claimingPlayerId.isNotEmpty() && player2Id.isNotEmpty()) player2Id else "")
            }
            put("miniGame", miniGame)
        }
        root.put("metadata", metadata)
        
        return root.toString()
    }

    companion object {
        fun fromJson(json: String): GameMessage? {
            return try {
                val root = JSONObject(json)
                val gs = root.getJSONObject("gameState")
                
                // Parse board
                val boardArray = gs.getJSONArray("board")
                val board = Array(3) { row ->
                    val rowArray = boardArray.getJSONArray(row)
                    Array(3) { col -> rowArray.optString(col, " ") }
                }
                
                val gameState = GameState(
                    board = board,
                    turn = gs.optString("turn", "0").toIntOrNull() ?: 0,
                    winner = gs.optString("winner", ""),
                    isDraw = gs.optBoolean("draw", false),
                    isReset = gs.optBoolean("reset", false)
                )
                
                // Parse metadata
                val metadata = root.optJSONObject("metadata") ?: JSONObject()
                val choices = metadata.optJSONArray("choices") ?: JSONArray()
                
                var p1 = ""
                var p2 = ""
                for (i in 0 until choices.length()) {
                    val choice = choices.getJSONObject(i)
                    val id = choice.optString("id", "")
                    val name = choice.optString("name", "")
                    when (id) {
                        "player1" -> p1 = name
                        "player2" -> p2 = name
                    }
                }
                
                val miniGame = metadata.optJSONObject("miniGame") ?: JSONObject()
                val claiming = miniGame.optString("player1Choice", "")
                
                GameMessage(gameState, p1, p2, claiming)
            } catch (e: Exception) {
                null
            }
        }
    }
}
