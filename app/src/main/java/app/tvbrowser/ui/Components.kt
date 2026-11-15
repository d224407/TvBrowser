package app.tvbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF4FC3F7)
private val Surface0 = Color(0xFF0B0F17)
private val Idle = Color(0xFF1E2430)
private val Selected = Color(0xFF2B3A55)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, background = Surface0, surface = Color(0xFF151B26)),
        content = content,
    )
}

@Composable
fun Modifier.initialFocus(key: Any? = Unit): Modifier {
    val r = remember { FocusRequester() }
    LaunchedEffect(key) { runCatching { r.requestFocus() } }
    return this.focusRequester(r)
}

@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Color.White else if (selected) Selected else Idle)
            .border(if (focused) 3.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
        content = { content(focused) },
    )
}

@Composable
fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Focusable(modifier, enabled, selected, onClick) { focused ->
        Text(
            label,
            color = if (!enabled) Color.Gray else if (focused) Color.Black else Color.White,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TvRow(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Focusable(modifier, onClick = onClick) { focused ->
        Column {
            Text(
                title,
                color = if (focused) Color.Black else Color.White,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    color = if (focused) Color(0xFF333333) else Color(0xFF9AA4B5),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun PanelFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20B0F17))
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Column {
            Text(title, color = Color.White, fontSize = 30.sp, modifier = Modifier.padding(bottom = 16.dp))
            content()
        }
    }
}
