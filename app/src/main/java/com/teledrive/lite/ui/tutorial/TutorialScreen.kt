package com.teledrive.lite.ui.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.teledrive.lite.R

@Composable
fun TutorialScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.tutorial_title), style = MaterialTheme.typography.headlineMedium)
        TutorialCard(R.string.tutorial_bot_title, R.string.tutorial_bot_body)
        TutorialCard(R.string.tutorial_channel_title, R.string.tutorial_channel_body)
        TutorialCard(R.string.tutorial_permissions_title, R.string.tutorial_permissions_body)
        TutorialCard(R.string.tutorial_chat_id_title, R.string.tutorial_chat_id_body)
        TutorialCard(R.string.tutorial_security_title, R.string.tutorial_security_body)
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back_to_setup))
        }
    }
}

@Composable
private fun TutorialCard(titleRes: Int, bodyRes: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
