package com.music.echo.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun DonationDialog(
    onDismiss: () -> Unit,
    onDonate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Consider donating towards keeping Vibe music alive")
        },
        text = {
            Text(text = "It costs us maintaining our servers and keep building regular updates")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDonate()
                }
            ) {
                Text(text = "Donate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Later")
            }
        }
    )
}
