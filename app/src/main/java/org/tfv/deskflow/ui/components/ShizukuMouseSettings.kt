package org.tfv.deskflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.tfv.deskflow.R
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.data.models.copy
import org.tfv.deskflow.services.shizuku.ShizukuInputBridge

/**
 * Toggle for Shizuku-based mouse injection plus live Shizuku status.
 * Applies immediately (writes AppPrefs directly) rather than via the
 * settings screen's Save button, since it doesn't affect the connection.
 */
@Composable
fun ShizukuMouseSettings(modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) { ShizukuInputBridge.init() }
  // Shizuku may have been started/granted while we were in the background.
  LifecycleResumeEffect(Unit) {
    ShizukuInputBridge.refresh()
    onPauseOrDispose {}
  }

  val enabledFlow = remember(ctx) { ctx.appPrefsStore.data.map { it.input.shizukuMouseEnabled } }
  val enabled by enabledFlow.collectAsState(initial = false)
  val status by ShizukuInputBridge.status.collectAsState()

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = stringResource(R.string.settings_shizuku_mouse_label),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = stringResource(R.string.settings_shizuku_mouse_description),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = enabled,
        onCheckedChange = { checked ->
          scope.launch {
            ctx.appPrefsStore.updateData { prefs ->
              prefs.copy { input = input.copy { shizukuMouseEnabled = checked } }
            }
          }
          if (checked && status == ShizukuInputBridge.Status.PermissionRequired) {
            ShizukuInputBridge.requestPermission()
          }
        },
      )
    }

    if (enabled) {
      val statusText =
        stringResource(
          when (status) {
            ShizukuInputBridge.Status.NotRunning -> R.string.settings_shizuku_status_not_running
            ShizukuInputBridge.Status.PermissionRequired ->
              R.string.settings_shizuku_status_permission_required
            ShizukuInputBridge.Status.PermissionDenied ->
              R.string.settings_shizuku_status_permission_denied
            ShizukuInputBridge.Status.Idle -> R.string.settings_shizuku_status_connecting
            ShizukuInputBridge.Status.Error -> R.string.settings_shizuku_status_error
            ShizukuInputBridge.Status.Ready -> R.string.settings_shizuku_status_ready
          }
        )
      Text(
        text = statusText,
        style = MaterialTheme.typography.bodySmall,
        color =
          if (status == ShizukuInputBridge.Status.Ready) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.error,
      )
      if (status == ShizukuInputBridge.Status.PermissionRequired) {
        Button(
          onClick = { ShizukuInputBridge.requestPermission() },
          shape = MaterialTheme.shapes.small,
          colors = ButtonDefaults.outlinedButtonColors(),
          modifier = Modifier.padding(top = 8.dp),
        ) {
          Text(stringResource(R.string.settings_shizuku_grant_permission))
        }
      }
    }
  }
}
