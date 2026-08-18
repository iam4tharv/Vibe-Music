package com.music.echo.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.music.echo.R
import com.music.echo.ui.component.Material3SettingsGroup
import com.music.echo.ui.component.Material3SettingsItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonationScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior
) {
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "Donations",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.upi_qr),
                        contentDescription = "UPI QR Code",
                        modifier = Modifier
                            .size(250.dp)
                            .padding(bottom = 32.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                    
                    Material3SettingsGroup(
                        title = "Support Development",
                        items = listOf(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.favorite),
                            title = { Text("Donate to Developer") },
                            description = { Text("UPI ID: dev.atharv@fam") },
                            onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("UPI ID", "dev.atharv@fam")
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "UPI ID copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ),
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.favorite_border),
                            title = { Text("Donate for Lossless") },
                            description = { Text("UPI ID: dev.atharv@fam") },
                            onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("UPI ID", "dev.atharv@fam")
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "UPI ID copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    )
                )
                }
            }
        }
    }
}
