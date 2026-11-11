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
import org.tfv.deskflow.services.shizuku.ShizukuKeyboardInjector

/**
 * Toggle for typing through the Shizuku virtual hardware keyboard, plus its
 * live status. Applies immediately, like [ShizukuMouseSettings].
 */
@Composable
fun ShizukuKeyboardSettings(modifier: Modifier = Modifier) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) { ShizukuInputBridge.init() }
  LifecycleResumeEffect(Unit) {
    ShizukuInputBridge.refresh()
    onPauseOrDispose {}
  }

  val enabledFlow =
    remember(ctx) { ctx.appPrefsStore.data.map { it.input.shizukuKeyboardEnabled } }
  val enabled by enabledFlow.collectAsState(initial = false)
  val shizukuStatus by ShizukuInputBridge.status.collectAsState()
  val keyboardStatus by ShizukuKeyboardInjector.status.collectAsState()

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
        Text(
          text = stringResource(R.string.settings_shizuku_keyboard_label),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = stringResource(R.string.settings_shizuku_keyboard_description),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = enabled,
        onCheckedChange = { checked ->
          scope.launch {
            ctx.appPrefsStore.updateData { prefs ->
              prefs.copy { input = input.copy { shizukuKeyboardEnabled = checked } }
            }
          }
          if (checked && shizukuStatus == ShizukuInputBridge.Status.PermissionRequired) {
            ShizukuInputBridge.requestPermission()
          }
        },
      )
    }

    if (enabled) {
      val (textRes, ok) =
        when (shizukuStatus) {
          ShizukuInputBridge.Status.NotRunning ->
            R.string.settings_shizuku_keyboard_status_not_running to false
          ShizukuInputBridge.Status.PermissionRequired ->
            R.string.settings_shizuku_keyboard_status_permission_required to false
          ShizukuInputBridge.Status.PermissionDenied ->
            R.string.settings_shizuku_status_permission_denied to false
          else ->
            when (keyboardStatus) {
              ShizukuKeyboardInjector.Status.Ready ->
                R.string.settings_shizuku_keyboard_status_ready to true
              ShizukuKeyboardInjector.Status.Starting ->
                R.string.settings_shizuku_keyboard_status_starting to true
              ShizukuKeyboardInjector.Status.Error ->
                R.string.settings_shizuku_keyboard_status_error to false
              else -> R.string.settings_shizuku_keyboard_status_waiting to true
            }
        }
      Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
      )
      if (shizukuStatus == ShizukuInputBridge.Status.PermissionRequired) {
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
