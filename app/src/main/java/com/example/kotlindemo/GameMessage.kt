package com.example.kotlindemo

import org.json.JSONArray
import org.json.JSONObject

data class GameState(
    val board: Array<Array<String>>,
    val turn: String,
    val winner: String,
    val draw: Boolean,
    val connectionEstablished: Boolean,
    val reset: Boolean
)

data class Choice(val id: String, val name: String)
data class MiniGame(val player1Choice: String, val player2Choice: String)

data class GameMessage(
    val gameState: GameState,
    val choices: List<Choice>,
    val miniGame: MiniGame
) {
    fun toJsonString(): String {
        val root = JSONObject()

        // gameState
        val gs = JSONObject()
        val boardJson = JSONArray()
        for (r in 0..2) {
            val row = JSONArray()
            for (c in 0..2) {
                row.put(gameState.board[r][c])
            }
            boardJson.put(row)
        }
        gs.put("board", boardJson)
        gs.put("turn", gameState.turn)
        gs.put("winner", gameState.winner)
        gs.put("draw", gameState.draw)
        gs.put("connectionEstablished", gameState.connectionEstablished)
        gs.put("reset", gameState.reset)
        root.put("gameState", gs)

        // metadata / choices
        val meta = JSONObject()
        val choicesArr = JSONArray()
        for (ch in choices) {
            val o = JSONObject()
            o.put("id", ch.id)
            o.put("name", ch.name)
            choicesArr.put(o)
        }
        meta.put("choices", choicesArr)

        // miniGame
        val mg = JSONObject()
        mg.put("player1Choice", miniGame.player1Choice)
        mg.put("player2Choice", miniGame.player2Choice)
        meta.put("miniGame", mg)

        root.put("metadata", meta)
        return root.toString()
    }

    companion object {
        fun fromJsonString(s: String): GameMessage? {
            try {
                val root = JSONObject(s)
                val gs = root.optJSONObject("gameState") ?: return null
                val boardJson = gs.optJSONArray("board") ?: return null
                val board = Array(3) { Array(3) { " " } }
                for (r in 0 until boardJson.length()) {
                    val row = boardJson.optJSONArray(r) ?: continue
                    for (c in 0 until row.length()) {
                        board[r][c] = row.optString(c, " ")
                    }
                }
                val turn = gs.optString("turn", "0")
                val winner = gs.optString("winner", " ")
                val draw = gs.optBoolean("draw", false)
                val conn = gs.optBoolean("connectionEstablished", false)
                val reset = gs.optBoolean("reset", false)
                val gameState = GameState(board, turn, winner, draw, conn, reset)

                val meta = root.optJSONObject("metadata") ?: JSONObject()
                val choicesJson = meta.optJSONArray("choices") ?: JSONArray()
                val choices = mutableListOf<Choice>()
                for (i in 0 until choicesJson.length()) {
                    val o = choicesJson.optJSONObject(i) ?: continue
                    choices.add(Choice(o.optString("id", ""), o.optString("name", "")))
                }
                val mg = meta.optJSONObject("miniGame") ?: JSONObject()
                val mini = MiniGame(mg.optString("player1Choice", ""), mg.optString("player2Choice", ""))

                return GameMessage(gameState, choices, mini)
            } catch (_: Exception) {
                return null
            }
        }
    }
}
