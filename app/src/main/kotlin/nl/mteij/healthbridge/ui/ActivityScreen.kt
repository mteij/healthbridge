package nl.mteij.healthbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.mteij.healthbridge.Graph
import nl.mteij.healthbridge.R
import nl.mteij.healthbridge.data.LogEntry

@Composable
fun ActivityScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val entries by Graph.log.entries.collectAsState()
    LazyColumn(
        modifier.statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                }
                Text(stringResource(R.string.sync_log), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { Graph.log.clear() }) { Text(stringResource(R.string.clear)) }
            }
        }
        if (entries.isEmpty()) item { Text(stringResource(R.string.log_empty)) }
        items(entries) { e ->
            val color = when (e.level) {
                LogEntry.Level.ERROR -> MaterialTheme.colorScheme.error
                LogEntry.Level.WARN -> MaterialTheme.colorScheme.tertiary
                LogEntry.Level.INFO -> MaterialTheme.colorScheme.onSurface
            }
            Column(Modifier.fillMaxWidth()) {
                Text(
                    dateTime(e.time) + (e.type?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(e.message, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
    }
}
