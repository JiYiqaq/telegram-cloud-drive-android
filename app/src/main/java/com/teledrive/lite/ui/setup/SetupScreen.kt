package com.teledrive.lite.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.lite.R
import com.teledrive.lite.repository.DetectedChannel
import com.teledrive.lite.settings.ConfigValidationError
import com.teledrive.lite.settings.PasswordStrength
import com.teledrive.lite.ui.util.SecureWindowEffect

@Composable
fun SetupRoute(
    viewModel: SetupViewModel,
    onOpenHelp: () -> Unit,
    onSetupComplete: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SecureWindowEffect()
    LaunchedEffect(state.completed) {
        if (state.completed) onSetupComplete()
    }
    SetupScreen(
        state = state,
        onBotTokenChanged = viewModel::onBotTokenChanged,
        onChannelIdChanged = viewModel::onChannelIdChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onPasswordConfirmationChanged = viewModel::onPasswordConfirmationChanged,
        onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
        onTestBot = viewModel::testBot,
        onTestChannel = viewModel::testChannel,
        onDetectChannels = viewModel::detectChannels,
        onConfirmChannel = viewModel::confirmDetectedChannel,
        onSave = viewModel::saveAndInitialize,
        onOpenHelp = onOpenHelp,
    )
}

@Composable
fun SetupScreen(
    state: SetupUiState,
    onBotTokenChanged: (String) -> Unit,
    onChannelIdChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordConfirmationChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onTestBot: () -> Unit,
    onTestChannel: () -> Unit,
    onDetectChannels: () -> Unit,
    onConfirmChannel: (Long) -> Unit,
    onSave: () -> Unit,
    onOpenHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.operation == SetupOperation.IDLE
    val passwordTransformation = if (state.passwordVisible) {
        VisualTransformation.None
    } else {
        PasswordVisualTransformation()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.setup_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenHelp, enabled = enabled) {
            Text(stringResource(R.string.setup_help))
        }

        OutlinedTextField(
            value = state.botToken,
            onValueChange = onBotTokenChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.bot_token_label)) },
            supportingText = {
                if (ConfigValidationError.INVALID_BOT_TOKEN in state.validationErrors) {
                    Text(stringResource(R.string.bot_token_error))
                }
            },
            isError = ConfigValidationError.INVALID_BOT_TOKEN in state.validationErrors,
        )
        FilledTonalButton(onClick = onTestBot, enabled = enabled) {
            Text(stringResource(R.string.test_bot))
        }
        state.botDisplayName?.let { name ->
            val identity = state.botUsername?.let { "$name (@$it)" } ?: name
            StatusCard(stringResource(R.string.bot_identity, identity))
        }

        OutlinedTextField(
            value = state.channelId,
            onValueChange = onChannelIdChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.channel_id_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            supportingText = {
                if (ConfigValidationError.INVALID_CHANNEL_ID in state.validationErrors) {
                    Text(stringResource(R.string.channel_id_error))
                }
            },
            isError = ConfigValidationError.INVALID_CHANNEL_ID in state.validationErrors,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(onClick = onTestChannel, enabled = enabled) {
                Text(stringResource(R.string.test_channel))
            }
            FilledTonalButton(onClick = onDetectChannels, enabled = enabled) {
                Text(stringResource(R.string.detect_channel))
            }
        }
        state.channelTitle?.let { StatusCard(stringResource(R.string.channel_identity, it)) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.detect_instructions_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.detect_instructions))
                Text(stringResource(R.string.detect_manual_fallback), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.detectedChannels.forEach { channel ->
            DetectedChannelCard(channel, enabled, onConfirmChannel)
        }

        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.sync_password_label)) },
            visualTransformation = passwordTransformation,
            trailingIcon = {
                TextButton(onClick = onTogglePasswordVisibility) {
                    Text(stringResource(if (state.passwordVisible) R.string.hide_password else R.string.show_password))
                }
            },
            supportingText = { PasswordError(state.validationErrors) },
            isError = ConfigValidationError.PASSWORD_REQUIRED in state.validationErrors ||
                ConfigValidationError.PASSWORD_TOO_SHORT in state.validationErrors,
        )
        Text(
            text = stringResource(
                R.string.password_strength,
                passwordStrengthLabel(state.passwordStrength),
            ),
            color = passwordStrengthColor(state.passwordStrength),
        )
        OutlinedTextField(
            value = state.passwordConfirmation,
            onValueChange = onPasswordConfirmationChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.confirm_password_label)) },
            visualTransformation = passwordTransformation,
            supportingText = {
                if (ConfigValidationError.PASSWORD_MISMATCH in state.validationErrors) {
                    Text(stringResource(R.string.password_mismatch_error))
                }
            },
            isError = ConfigValidationError.PASSWORD_MISMATCH in state.validationErrors,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.setup_security_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.setup_security_note))
            }
        }

        if (!enabled) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.userMessage?.let { StatusCard(it) }
        Button(
            onClick = onSave,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.operation == SetupOperation.SAVING) {
                    stringResource(R.string.saving_initializing)
                } else {
                    stringResource(R.string.save_initialize)
                },
            )
        }
    }
}

@Composable
private fun DetectedChannelCard(
    channel: DetectedChannel,
    enabled: Boolean,
    onConfirm: (Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(channel.title ?: stringResource(R.string.unnamed_channel), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.detected_channel_id, channel.channelId.toString()))
            Button(onClick = { onConfirm(channel.channelId) }, enabled = enabled) {
                Text(stringResource(R.string.confirm_channel))
            }
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun PasswordError(errors: Set<ConfigValidationError>) {
    when {
        ConfigValidationError.PASSWORD_REQUIRED in errors -> Text(stringResource(R.string.password_required_error))
        ConfigValidationError.PASSWORD_TOO_SHORT in errors -> Text(stringResource(R.string.password_short_error))
    }
}

@Composable
private fun passwordStrengthLabel(strength: PasswordStrength): String = when (strength) {
    PasswordStrength.WEAK -> stringResource(R.string.password_weak)
    PasswordStrength.MEDIUM -> stringResource(R.string.password_medium)
    PasswordStrength.STRONG -> stringResource(R.string.password_strong)
}

@Composable
private fun passwordStrengthColor(strength: PasswordStrength) = when (strength) {
    PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
    PasswordStrength.MEDIUM -> MaterialTheme.colorScheme.tertiary
    PasswordStrength.STRONG -> MaterialTheme.colorScheme.primary
}
