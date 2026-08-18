package com.music.echo.ui.screens.equalizer.autoeq

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.music.echo.eq.autoeq.AutoEqEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoEqScreen(
    onBackClick: () -> Unit,
    viewModel: AutoEqViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearMessages() },
            title = { Text("Error") },
            text = { Text(state.error ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearMessages() }) {
                    Text("OK")
                }
            }
        )
    }

    if (state.importSuccessMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearMessages() },
            title = { Text("Success") },
            text = { Text(state.importSuccessMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearMessages() }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoEq Database") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.search(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search headphone model...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(state.filteredEntries) { entry ->
                        AutoEqItem(
                            entry = entry,
                            onImportClick = { viewModel.importProfile(entry) },
                            isImporting = state.isImporting
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AutoEqItem(
    entry: AutoEqEntry,
    onImportClick: () -> Unit,
    isImporting: Boolean
) {
    ListItem(
        headlineContent = { Text(entry.name) },
        supportingContent = { 
            Text("by ${entry.source}" + if (entry.rig.isNotBlank()) " on ${entry.rig}" else "") 
        },
        trailingContent = {
            IconButton(
                onClick = onImportClick,
                enabled = !isImporting
            ) {
                Icon(Icons.Default.Download, contentDescription = "Import")
            }
        },
        modifier = Modifier.clickable(enabled = !isImporting) { onImportClick() }
    )
}
