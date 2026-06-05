package com.wangpan.videohelper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wangpan.videohelper.R
import com.wangpan.videohelper.data.settings.AppSettings

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val saved by viewModel.settings.collectAsState()

    // Local editable copy; re-seeded whenever the persisted settings change.
    var draft by remember(saved) { mutableStateOf(saved) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ASR section
        SectionCard(stringResource(R.string.settings_asr_title)) {
            LabeledField(stringResource(R.string.settings_base_url), draft.asrBaseUrl) {
                draft = draft.copy(asrBaseUrl = it)
            }
            LabeledField(stringResource(R.string.settings_model), draft.asrModel) {
                draft = draft.copy(asrModel = it)
            }
            LabeledField(
                stringResource(R.string.settings_api_key),
                draft.asrApiKey,
                isSecret = true
            ) { draft = draft.copy(asrApiKey = it) }
            LabeledField(stringResource(R.string.settings_language), draft.language) {
                draft = draft.copy(language = it)
            }
        }

        // LLM section
        SectionCard(stringResource(R.string.settings_llm_title)) {
            LabeledField(stringResource(R.string.settings_base_url), draft.llmBaseUrl) {
                draft = draft.copy(llmBaseUrl = it)
            }
            LabeledField(stringResource(R.string.settings_model), draft.llmModel) {
                draft = draft.copy(llmModel = it)
            }
            LabeledField(
                stringResource(R.string.settings_api_key),
                draft.llmApiKey,
                isSecret = true
            ) { draft = draft.copy(llmApiKey = it) }
        }

        // Recording defaults
        SectionCard(stringResource(R.string.tab_home)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.home_mic_toggle))
                Switch(
                    checked = draft.recordMicByDefault,
                    onCheckedChange = { draft = draft.copy(recordMicByDefault = it) }
                )
            }
        }

        Button(
            onClick = { viewModel.save(draft) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    isSecret: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (isSecret) {
            KeyboardOptions(keyboardType = KeyboardType.Password)
        } else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth()
    )
}
