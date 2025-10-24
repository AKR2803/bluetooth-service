package com.example.kotlindemo

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small helpers to build/parse the JSON schema described in the spec.
 * Uses org.json so we avoid adding extra dependencies.
 */

data class GameState(
    val board: Array<Array<String>>, // 3x3
    val turn: String,                // turn counter as string
    val winner: String,
    val draw: Boolean,
    val connectionEstablished: Boolean,
    val reset: Boolean
)

data class Choice(val id: String, val name: String)
data class MiniGame(val player1Choice: String, val player2Choice: String)

data class GameMessage(val gameState: GameState, val choices: List<Choice>, val miniGame: MiniGame) {

    fun toJsonString(): String {
        val root = JSONObject()
        val gs = JSONObject()
        val boardArr = JSONArray()
        for (r in 0..2) {
            val row = JSONArray()
            for (c in 0..2) row.put(gameState.board[r][c])
            boardArr.put(row)
        }
        gs.put("board", boardArr)
        gs.put("turn", gameState.turn)
        gs.put("winner", gameState.winner)
        gs.put("draw", gameState.draw)
        gs.put("connectionEstablished", gameState.connectionEstablished)
        gs.put("reset", gameState.reset)
        root.put("gameState", gs)

        val meta = JSONObject()
        val choicesArr = JSONArray()
        for (ch in choices) {
            val o = JSONObject()
            o.put("id", ch.id)
            o.put("name", ch.name)
            choicesArr.put(o)
        }
        meta.put("choices", choicesArr)

        val mini = JSONObject()
        mini.put("player1Choice", miniGame.player1Choice)
        mini.put("player2Choice", miniGame.player2Choice)
        meta.put("miniGame", mini)

        root.put("metadata", meta)
        return root.toString()
    }

    companion object {
        fun fromJsonString(s: String): GameMessage? {
            return try {
                val root = JSONObject(s)
                val gs = root.getJSONObject("gameState")
                val boardJson = gs.getJSONArray("board")
                val board = Array(3) { Array(3) { " " } }
                for (r in 0 until boardJson.length()) {
                    val row = boardJson.getJSONArray(r)
                    for (c in 0 until row.length()) {
                        board[r][c] = row.getString(c)
                    }
                }
                val gameState = GameState(
                    board = board,
                    turn = gs.optString("turn", "0"),
                    winner = gs.optString("winner", " "),
                    draw = gs.optBoolean("draw", false),
                    connectionEstablished = gs.optBoolean("connectionEstablished", false),
                    reset = gs.optBoolean("reset", false)
                )
                val meta = root.getJSONObject("metadata")
                val choicesJson = meta.getJSONArray("choices")
                val choices = mutableListOf<Choice>()
                for (i in 0 until choicesJson.length()) {
                    val o = choicesJson.getJSONObject(i)
                    choices.add(Choice(o.getString("id"), o.getString("name")))
                }
                val miniJson = meta.getJSONObject("miniGame")
                val mini = MiniGame(
                    player1Choice = miniJson.optString("player1Choice", ""),
                    player2Choice = miniJson.optString("player2Choice", "")
                )
                GameMessage(gameState, choices, mini)
            } catch (_: Exception) {
                null
            }
        }
    }
}
