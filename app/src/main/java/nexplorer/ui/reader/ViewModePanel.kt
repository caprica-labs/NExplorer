package nexplorer.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nexplorer.reader.Direction
import nexplorer.reader.Movement
import nexplorer.reader.ReaderTheme
import nexplorer.reader.ViewSettings
import nexplorer.reader.withTextSize
import nexplorer.ui.OptionLabel as Label
import nexplorer.ui.Segments

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewModePanel(
    settings: ViewSettings,
    theme: ReaderTheme,
    onSettings: (ViewSettings) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Label("Direction")
            Segments(
                listOf("Vertical", "Horizontal"),
                selected = settings.direction.ordinal,
            ) { onSettings(settings.copy(direction = Direction.entries[it])) }

            Label("Movement")
            Segments(
                listOf("Scroll", "Page by page"),
                selected = settings.movement.ordinal,
            ) { onSettings(settings.copy(movement = Movement.entries[it])) }

            Label("Reflow")
            Segments(listOf("Off", "On"), selected = if (settings.reflow) 1 else 0) {
                onSettings(settings.copy(reflow = it == 1))
            }

            Label("Text size")
            Segments(
                listOf("A-", "${settings.textSizePct}%", "A+"),
                selected = -1,
                enabled = settings.reflow,
            ) {
                when (it) {
                    0 -> onSettings(settings.withTextSize(-10))
                    2 -> onSettings(settings.withTextSize(10))
                }
            }

            Label("Theme")
            Segments(listOf("Light", "Dark"), selected = theme.ordinal) {
                onTheme(ReaderTheme.entries[it])
            }
        }
    }
}

