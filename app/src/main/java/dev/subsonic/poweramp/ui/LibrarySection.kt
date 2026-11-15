package dev.subsonic.poweramp.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.subsonic.poweramp.stream.CacheSettings
import dev.subsonic.poweramp.sync.SyncSettings

@Composable
private fun QualityRow(label: String, selected: Int, onChange: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val choices = CacheSettings.QUALITY_CHOICES
        choices.forEachIndexed { index, kbps ->
            SegmentedButton(
                selected = selected == kbps,
                onClick = { onChange(kbps) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            ) { Text(if (kbps == CacheSettings.ORIGINAL) "Orig." else "$kbps", maxLines = 1) }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}

@Composable
fun LibrarySection(
    state: LibraryUiState,
    onSyncNow: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onAskPowerampRescan: () -> Unit,
    onScrobbleChange: (Boolean) -> Unit,
    cache: CacheUiState,
    onCacheMaxGbChange: (Int) -> Unit,
    onWholeTracksOnMobileChange: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onWifiQualityChange: (Int) -> Unit,
    onMobileQualityChange: (Int) -> Unit,
    onAutoUpgradeChange: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }
    val status = state.status

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Library", style = MaterialTheme.typography.headlineSmall)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.counts?.let { c ->
                Text(
                    "${c.tracks} tracks · ${c.albums} albums · ${c.artists} artists · ${c.playlists} playlists",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                when {
                    status.running -> "Syncing…"
                    status.lastSuccessAt != null -> {
                        val now = System.currentTimeMillis()
                        if (now - status.lastSuccessAt < DateUtils.MINUTE_IN_MILLIS) "Last synced just now"
                        else "Last synced " + DateUtils.getRelativeTimeSpanString(status.lastSuccessAt, now, DateUtils.MINUTE_IN_MILLIS)
                    }
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            status.lastSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            status.lastError?.let {
                Text(
                    (if (status.authFailed) "Sync paused: " else "Last sync failed: ") + it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSyncNow, enabled = !status.running) { Text("Sync now") }
        if (status.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }

    Text("Sync automatically every", style = MaterialTheme.typography.bodyMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val choices = SyncSettings.INTERVAL_CHOICES
        choices.forEachIndexed { index, hours ->
            SegmentedButton(
                selected = state.settings.intervalHours == hours,
                onClick = { onIntervalChange(hours) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            ) { Text("$hours h") }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Only sync on Wi-Fi", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Sync downloads track information only, not music.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = state.settings.wifiOnly, onCheckedChange = onWifiOnlyChange)
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Poweramp", style = MaterialTheme.typography.headlineSmall)
    Text(
        "To add this library in Poweramp: Settings → Library → Music Folders → + → open the menu → " +
            "Sonic-Amp → Sonic-Amp → Use this folder.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        if (state.powerampRescanPending) "The library changed since Poweramp last scanned it."
        else "Poweramp has scanned the latest library.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = onAskPowerampRescan) { Text("Ask Poweramp to rescan") }
    Text(
        "Playlists from the server appear in a Playlists folder; Poweramp imports them when it scans.",
        style = MaterialTheme.typography.bodySmall,
    )

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Scrobble plays to the server", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Shows what's playing and counts plays (half the track or 4 minutes). " +
                    "Plays made offline are sent later.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = state.scrobbleEnabled, onCheckedChange = onScrobbleChange)
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Streaming quality", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Original plays the file as it is on the server. A bitrate asks the server to convert to MP3 " +
            "first, which uses far less data. A track already fully downloaded in original quality " +
            "always plays from the phone.",
        style = MaterialTheme.typography.bodyMedium,
    )
    QualityRow("On Wi-Fi", cache.settings.wifiQuality, onWifiQualityChange)
    QualityRow("On mobile data", cache.settings.mobileQuality, onMobileQualityChange)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Upgrade to original quality", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Tracks cached at a lower bitrate are replaced with the original in the background, " +
                    "on Wi-Fi while charging. Never removes other music to make room.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = cache.settings.autoUpgrade, onCheckedChange = onAutoUpgradeChange)
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Offline cache", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Tracks you play are kept on this phone, so replays and fully downloaded tracks play without " +
            "the server. Oldest are removed first when the cache is full.",
        style = MaterialTheme.typography.bodyMedium,
    )
    cache.stats?.let { s ->
        Text(
            "${formatBytes(s.bytes)} used · ${s.completeTracks} of ${s.tracks} tracks fully downloaded",
            style = MaterialTheme.typography.titleSmall,
        )
    }
    Text("Cache size", style = MaterialTheme.typography.bodyMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val choices = CacheSettings.MAX_GB_CHOICES
        choices.forEachIndexed { index, gb ->
            SegmentedButton(
                selected = cache.settings.maxGb == gb,
                onClick = { onCacheMaxGbChange(gb) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
            ) { Text("$gb GB") }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Download whole tracks on mobile data", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Off: on mobile data only the next 8 MB or so is downloaded ahead, so skipped tracks cost little. " +
                    "On Wi-Fi whole tracks are always downloaded.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = cache.settings.wholeTracksOnMobile, onCheckedChange = onWholeTracksOnMobileChange)
    }
    TextButton(onClick = onClearCache) { Text("Clear cache") }

    TextButton(onClick = { confirmForget = true }) {
        Text("Forget server", color = MaterialTheme.colorScheme.error)
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this server?") },
            text = { Text("This removes the saved login and the synced library from this device. Nothing on the server is changed.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget() }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
