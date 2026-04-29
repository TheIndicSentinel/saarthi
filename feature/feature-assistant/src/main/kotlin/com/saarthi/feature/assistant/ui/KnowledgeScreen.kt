package com.saarthi.feature.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.core.memory.domain.MemoryEntry
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.viewmodel.AssistantViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    onBack: () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel()
) {
    val memories by viewModel.allMemories.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saarthi Knowledge", color = SaarthiColors.Gold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SaarthiColors.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SaarthiColors.NavyDark)
            )
        },
        containerColor = SaarthiColors.DeepSpace
    ) { innerPadding ->
        if (memories.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(64.dp), tint = SaarthiColors.TextMuted)
                    Spacer(Modifier.height(16.dp))
                    Text("No personal knowledge stored yet.", color = SaarthiColors.TextMuted)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(memories) { memory ->
                    MemoryItem(
                        memory = memory,
                        onDelete = { viewModel.deleteMemory(memory.key) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryItem(
    memory: MemoryEntry,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SaarthiColors.NavyLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    memory.key.replace("_", " ").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = SaarthiColors.Gold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    memory.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SaarthiColors.TextPrimary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = SaarthiColors.TextMuted)
            }
        }
    }
}
