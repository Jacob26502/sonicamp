package dev.subsonic.poweramp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.subsonic.poweramp.subsonic.ServerInfo

@Composable
fun LoginRoute(
    viewModel: LoginViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by libraryViewModel.state.collectAsStateWithLifecycle()

    // Ask once for notification permission (Android 13+) when saving, so a rejected login
    // during a background sync can be reported. Saving goes ahead whatever the answer.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    val onSave = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.save()
    }

    LoginScreen(
        state = state,
        onEdit = viewModel::edit,
        onTest = viewModel::testConnection,
        onSave = onSave,
        librarySection = {
            if (library.configured) {
                LibrarySection(
                    state = library,
                    onSyncNow = libraryViewModel::syncNow,
                    onIntervalChange = libraryViewModel::setInterval,
                    onWifiOnlyChange = libraryViewModel::setWifiOnly,
                    onForget = viewModel::forgetServer,
                )
            }
        },
    )
}

@Composable
fun LoginScreen(
    state: LoginUiState,
    onEdit: ((LoginForm) -> LoginForm) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    librarySection: @Composable () -> Unit = {},
) {
    val form = state.form
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Subsonic server", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Connect to a Navidrome or other Subsonic-compatible server.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = form.serverUrl,
                onValueChange = { v -> onEdit { it.copy(serverUrl = v) } },
                label = { Text("Server address") },
                placeholder = { Text("https://music.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.insecureUrl) {
                Text(
                    "Plain http: your login details and music are sent unencrypted. " +
                        "Fine on a home network, risky over the internet.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = form.authMode == mode,
                        onClick = { onEdit { it.copy(authMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, AuthMode.entries.size),
                    ) {
                        Text(if (mode == AuthMode.PASSWORD) "Username + password" else "API key")
                    }
                }
            }

            when (form.authMode) {
                AuthMode.PASSWORD -> {
                    OutlinedTextField(
                        value = form.username,
                        onValueChange = { v -> onEdit { it.copy(username = v) } },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.password,
                        onValueChange = { v -> onEdit { it.copy(password = v) } },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AuthMode.API_KEY -> {
                    OutlinedTextField(
                        value = form.apiKey,
                        onValueChange = { v -> onEdit { it.copy(apiKey = v) } },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Create a key in your server's web interface. Navidrome doesn't support API keys yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest, enabled = state.canTest) { Text("Test connection") }
                Button(onClick = onSave, enabled = state.canSave) { Text("Save") }
                if (state.testing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            if (state.saved) {
                Text("Saved on this device.", style = MaterialTheme.typography.bodyMedium)
            }

            when (val result = state.result) {
                is TestResult.Success -> SuccessCard(result.info, usingApiKey = form.authMode == AuthMode.API_KEY)
                is TestResult.Failure -> ErrorCard(result.message)
                null -> Unit
            }

            librarySection()
        }
    }
}

@Composable
private fun SuccessCard(info: ServerInfo, usingApiKey: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connected to ${info.displayName}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Subsonic API ${info.apiVersion ?: "?"}" + if (info.openSubsonic) " · OpenSubsonic" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (info.extensions.isNotEmpty()) {
                Text(
                    "Extensions: " + info.extensions.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!usingApiKey) {
                Text(
                    if (info.supportsApiKey) "This server also supports API keys, which avoid storing your password."
                    else "This server doesn't support API keys, so your password is stored (encrypted) on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
