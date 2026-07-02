package com.saarthi.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.settings
import com.saarthi.core.memory.domain.MemoryEntry
import com.saarthi.core.memory.domain.MemoryRepository
import com.saarthi.core.ui.components.SaarthiTopBar
import com.saarthi.core.ui.theme.SaarthiColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backing state for the memory viewer: the cross-chat USER_SCOPE profile
 * facts (name, city, likes, …) that follow the user into every conversation
 * and drive the personalised greeting. Uses the MemoryRepository "admin
 * surface" that was reserved for exactly this settings screen.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    val facts: StateFlow<List<MemoryEntry>> = memoryRepository
        .observeBySession(MemoryRepository.USER_SCOPE)
        .map { list -> list.sortedByDescending { it.updatedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(key: String) {
        viewModelScope.launch {
            runCatching { memoryRepository.delete(MemoryRepository.USER_SCOPE, key) }
        }
    }
}

/**
 * "What Saarthi knows about me" — the industry-standard memory transparency
 * surface (ChatGPT / Gemini both ship one): every durable fact the assistant
 * has remembered, each individually deletable. Deleting here immediately
 * affects both the home greeting (name) and prompt memory injection, because
 * all three read the same USER_SCOPE store.
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    language: SupportedLanguage,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val s = language.settings

    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = s.memoryTitle, subtitle = s.memoryTitleSub, onBack = onBack)
        if (facts.isEmpty()) {
            Text(
                text = s.memoryEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = SaarthiColors.TextSecondary,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                items(facts, key = { it.key }) { entry ->
                    MemoryFactRow(entry = entry, onDelete = { viewModel.delete(entry.key) })
                }
            }
        }
    }
}

@Composable
private fun MemoryFactRow(entry: MemoryEntry, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // "first_name" → "First name" — keys are internal snake_case ids.
            val label = entry.key.replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = SaarthiColors.TextSecondary,
                fontSize = 12.sp,
            )
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = SaarthiColors.TextPrimary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = SaarthiColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
