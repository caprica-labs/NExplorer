package nexplorer.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import nexplorer.reader.parsePageInput
import nexplorer.ui.theme.AccentBlue

@Composable
fun JumpToPageDialog(current: Int, pageCount: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = (current + 1).toString()
    var input by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun go() {
        val page = parsePageInput(input.text, pageCount)
        if (page == null) error = true else onJump(page)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to page") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = false },
                    singleLine = true,
                    isError = error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentBlue, cursorColor = AccentBlue),
                    modifier = Modifier.focusRequester(focus),
                )
                Text(
                    if (error) "Enter a number from 1 to $pageCount" else "1 to $pageCount",
                    fontSize = 12.sp,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = ::go) { Text("Go", color = AccentBlue) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}
