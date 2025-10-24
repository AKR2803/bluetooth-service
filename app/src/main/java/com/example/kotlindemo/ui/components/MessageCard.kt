package com.example.kotlindemo.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.kotlindemo.model.Message

@Composable
fun MessageCard(
    msg: Message,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // load image from assets
    // Load once per composition (same asset for all items)
    val avatar = remember {
        context.assets.open("profile_picture.jpeg").use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    // dp (density-independent pixels)
    // dp: keeps UI consistent across all screen sizes and resolutions
    // 1 dp is roughly equal to 1 pixel on a 160 dpi screen (a medium-density display)
    // dpi: dots per inch
    Row(modifier = modifier.padding(8.dp)) {
        Image(
            bitmap = avatar.asImageBitmap(),
            contentDescription = "Profile Pic",
            modifier = Modifier
                .width(40.dp)
                .height(40.dp)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
        )

        Spacer(Modifier.width(8.dp))

        var isExpanded by remember { mutableStateOf(false) }
        val surfaceColor by animateColorAsState(
            if(isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        )
        Column(modifier = Modifier.clickable{ isExpanded = !isExpanded }) {
            Text(
                text = msg.author,
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = surfaceColor,
                shadowElevation = 3.dp,
                modifier = Modifier.animateContentSize().padding(1.dp)
            ) {
                Text(
                    text = msg.body,
                    modifier = Modifier.padding(4.dp),
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

    }
}

@Preview
@Composable
fun PreviewMessageCard(){
    MessageCard(Message("AKR2803", "Hi from preview!"))
}