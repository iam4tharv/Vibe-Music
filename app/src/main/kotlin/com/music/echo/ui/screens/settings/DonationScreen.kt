package com.music.echo.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip


import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.compose.ui.Alignment

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
    var showCelebration by remember { mutableStateOf(false) }

    val leaderboardData by produceState<List<Pair<String, String>>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            try {
                val response = URL("https://raw.githubusercontent.com/iam4tharv/Vibe-Music/main/DonationsBoard.md").readText()
                response.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { rawLine ->
                        val line = rawLine.replace(Regex("""^[\d\.\-\*\)\s]+"""), "").trim()
                        val match = Regex("""^(.*?)(?:[\s\-:₹$]+)(\d+(?:\.\d+)?)\s*$""").find(line)
                        if (match != null) {
                            val name = match.groupValues[1].trim()
                            val amount = match.groupValues[2].trim()
                            name to amount
                        } else {
                            val lastSpace = line.lastIndexOf(' ')
                            if (lastSpace != -1) {
                                line.substring(0, lastSpace).trim() to line.substring(lastSpace + 1).trim()
                            } else {
                                line to ""
                            }
                        }
                    }
                    .filter { it.first.isNotBlank() }
                    .sortedByDescending { it.second.toIntOrNull() ?: 0 }
            } catch (e: Exception) {
                emptyList()
            
}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = "Icon")
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
                .verticalScroll(rememberScrollState())
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
FundingProgressBar(
                        leaderboardData = leaderboardData,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    Material3SettingsGroup(
                        title = "Support Development",
                        items = listOf(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.favorite),
                            title = { Text("Donate to Developer") },
                            description = { Text("UPI ID: dev.atharv@fam") },
                            onClick = {
                                try {
                                    showCelebration = true
                                    val uri = android.net.Uri.parse("upi://pay?pa=dev.atharv@fam&pn=Atharv&am=99&cu=INR&tn=Donation%20to%20Vibe%20Music&tr=ORDER123")
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No UPI app found", Toast.LENGTH_SHORT).show()
                                
}
                            }
                        ),
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.favorite_border),
                            title = { Text("Donate for Lossless") },
                            description = { Text("UPI ID: dev.atharv@fam") },
                            onClick = {
                                try {
                                    showCelebration = true
                                    val uri = android.net.Uri.parse("upi://pay?pa=dev.atharv@fam&pn=Atharv&am=99&cu=INR&tn=Donation%20to%20Vibe%20Music&tr=ORDER123")
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No UPI app found", Toast.LENGTH_SHORT).show()
                                
}
                            }
                        )
                    )
                )

                    if (leaderboardData.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Thank You (every rupee counts)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                leaderboardData.forEachIndexed { index, (name, amount) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${index + 1}. $name",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "₹$amount",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (index < leaderboardData.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                }
            }
        }
    }

        if (showCelebration) {
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.confetti))
            LottieAnimation(
                composition = composition,
                iterations = 1,
                modifier = Modifier.fillMaxSize()
            )
            LaunchedEffect(Unit) {
                delay(3500)
                showCelebration = false
            }
        }
    }
}


@Composable
fun FundingProgressBar(
    leaderboardData: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showBannerText: Boolean = false
) {
    val totalDonated = leaderboardData.sumOf { pair ->
        pair.second.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
    val goal = 10000
    val progress = (totalDonated.toFloat() / goal).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable { onClick() } else it },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (showBannerText) {
                Text(
                    text = "Please help us cover our server costs and keep the project alive ❤️",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Funding Goal",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "₹$totalDonated / ₹$goal",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            )
        }
    }
}
