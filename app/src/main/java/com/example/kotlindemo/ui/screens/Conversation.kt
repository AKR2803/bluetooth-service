package com.example.kotlindemo.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.kotlindemo.data.SampleMessages
import com.example.kotlindemo.model.Message
import com.example.kotlindemo.ui.components.MessageCard

@Composable
fun Conversation(
    messages: List<Message>,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(messages) { msg ->
            MessageCard(msg = msg)
        }
    }
}

@Preview(
    name="Conversation Sample",
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun PreviewConversation(){
    Conversation(messages = SampleMessages)
}