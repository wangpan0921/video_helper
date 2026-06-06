package com.wangpan.videohelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wangpan.videohelper.R
import com.wangpan.videohelper.VideoHelperApp

@Composable
fun HomeScreen(
    onStartRecording: (includeMic: Boolean) -> Unit,
    onStopRecording: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    val recording by VideoHelperApp.recordingActive.collectAsState()
    val lastPath by VideoHelperApp.lastRecordingPath.collectAsState()

    var includeMic by remember(settings.recordMicByDefault) {
        mutableStateOf(settings.recordMicByDefault)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.home_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.home_mic_toggle))
                Switch(
                    checked = includeMic,
                    onCheckedChange = { includeMic = it },
                    enabled = !recording
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        if (recording) {
            // Item 2: returning to the app during recording shows a "recording" indicator.
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.home_recording),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStopRecording, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_stop))
            }
        } else {
            Button(
                onClick = { onStartRecording(includeMic) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.home_start))
            }
            // Item 3: after recording, returning to the app shows the last recording's path.
            lastPath?.let { path ->
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.home_last_recording),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
