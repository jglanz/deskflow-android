/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.tfv.deskflow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.tfv.deskflow.R
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.data.DeskflowClientCertificateProvider
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.data.aidl.ScreenState
import org.tfv.deskflow.data.models.copy
import org.tfv.deskflow.data.aidl.ServerState
import org.tfv.deskflow.ui.annotations.PreviewAll
import org.tfv.deskflow.ui.components.AppState
import org.tfv.deskflow.ui.components.DeskflowCard
import org.tfv.deskflow.ui.components.DeskflowCardSubtitle
import org.tfv.deskflow.ui.components.DeskflowCardTitle
import org.tfv.deskflow.ui.components.IAppState
import org.tfv.deskflow.ui.components.LocalSnackbarHostState
import org.tfv.deskflow.ui.components.deskflowCardDefaultContainerModifier
import org.tfv.deskflow.ui.components.deskflowCardStyleDefaults
import org.tfv.deskflow.ui.components.preview.PreviewDeskflowThemedRoot

private val log = KLoggingManager.logger("SettingsScreen")

@Serializable data object SettingsScreenRoute

fun NavController.navigateToSettingsScreen(navOptions: NavOptions) =
  navigate(route = SettingsScreenRoute, navOptions)

fun NavGraphBuilder.settingsScreen(appState: IAppState) {
  composable<SettingsScreenRoute> { SettingsScreenRoute(appState = appState) }
}

@Composable
internal fun SettingsScreenRoute(appState: IAppState) {
  val state by appState.connectionStateFlow.collectAsStateWithLifecycle()
  val screenState = state.screen
  val isConnected = state.isConnected
  val snackbarHost = LocalSnackbarHostState.current
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current
  SettingsScreen(
    uiState =
      if (screenState == null) SettingsUiState.Loading
      else SettingsUiState.Success(isConnected, screenState),
    onChange = { newScreenState ->
      if (appState !is AppState) return@SettingsScreen

      appState.serviceClient.updateScreenState(newScreenState)
      scope.launch {
        snackbarHost.showSnackbar(ctx.getString(R.string.toast_settings_saved))
      }

    },
    onCancel = { appState.navigateToHome() },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  uiState: SettingsUiState,
  onChange: (ScreenState) -> Unit,
  onCancel: () -> Unit,
) {

  when (uiState) {
    is SettingsUiState.Loading -> {
      CircularProgressIndicator(
        modifier = Modifier.width(64.dp),
        color = MaterialTheme.colorScheme.secondary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
    }

    is SettingsUiState.Success -> {
      val textFieldColors =
        TextFieldDefaults
          .colors( //        unfocusedContainerColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
          )
      val focusRequester = remember { FocusRequester() }
      LaunchedEffect(Unit) {
        focusRequester.requestFocus() //        keyboardController?.show()
      }
      val screenValue = uiState.screen
      var screenName by remember(screenValue) { mutableStateOf(screenValue.name) }
      var address by remember(screenValue) { mutableStateOf(screenValue.server.address) }
      var port by remember(screenValue) { mutableIntStateOf(screenValue.server.port) }
      var useTls by remember(screenValue) { mutableStateOf(screenValue.server.useTls) }
      var isDirty by remember(screenValue) { mutableStateOf(false) }

      // Pointer speed is a LOCAL setting (not server config), so it reads/writes
      // the app prefs DataStore directly rather than going through onChange.
      val ctx = LocalContext.current
      val scope = rememberCoroutineScope()
      val storedSensitivity by remember(ctx) {
        ctx.appPrefsStore.data
          .map { raw -> if (raw.mouseSensitivity == 0f) 1f else raw.mouseSensitivity }
          .catch { emit(1f) }
      }.collectAsStateWithLifecycle(initialValue = 1f)
      var sensitivitySlider by remember(storedSensitivity) {
        mutableStateOf(storedSensitivity)
      }

      val saveChanges ={
        onChange(
          ScreenState().apply {
            name = screenName
            server =
              ServerState().apply {
                this.address = address
                this.port = port
                this.useTls = useTls
              }
          }
        )
      }

      val textStyle = MaterialTheme.typography.bodyMedium
      val textStyleCharWidthDp =
        with(LocalDensity.current) { textStyle.fontSize.toDp() }

      val innerScrollState = rememberScrollState()

      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment =
          Alignment
            .CenterHorizontally, // (optionally) center them horizontally too
      ) {
        DeskflowCard(
          header = {
            DeskflowCardTitle(R.string.settings_screen_title)
            DeskflowCardSubtitle(R.string.settings_screen_instructions)
          },
          style =
            deskflowCardStyleDefaults(
              containerModifier =
                deskflowCardDefaultContainerModifier().padding(vertical = 16.dp)
            ),
          useFooterStyle = false,
          useContentStyle = false,
          footer = {
            /** Bottom action bar */
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .background(
                    color = MaterialTheme.colorScheme.secondaryContainer
                  )
            ) {
              CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSecondary
              ) {
                Row(
                  horizontalArrangement =
                    Arrangement.spacedBy(8.dp, alignment = Alignment.End),
                  modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                  Button(
                    onClick = { onCancel() },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.textButtonColors(),
                  ) {
                    Text(stringResource(R.string.button_cancel))
                  }
                  Button(
                    onClick = {
                      when {
                        isDirty -> saveChanges()
                        else -> onCancel()
                      }
                    },
                    shape = MaterialTheme.shapes.small,
                    colors =
                      ButtonDefaults.filledTonalButtonColors(
                        containerColor =
                          MaterialTheme.colorScheme.onSecondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                      ),
                  ) {
                    Text(
                      stringResource(
                        when {
                          isDirty -> R.string.button_save
                          else -> R.string.button_done
                        }
                      )
                    )
                  }
                }
              }
            }
          },
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
              Modifier.fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(innerScrollState),
          ) {
            // This device's client-certificate fingerprint (shown once it exists,
            // i.e. after the first TLS connect) for registration on a PeerAuth
            // (mutual-TLS) server. Read-only: never generates a cert from the UI.
            val clientCertFingerprint = remember {
              runCatching {
                DeskflowClientCertificateProvider(ctx).fingerprintIfPresent()
              }.getOrNull()
            }
            if (clientCertFingerprint != null) {
              Text(
                text =
                  stringResource(
                    R.string.settings_screen_client_cert_fingerprint,
                    clientCertFingerprint,
                  ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
              )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                stringResource(R.string.settings_screen_pointer_speed),
                style = MaterialTheme.typography.bodyMedium,
              )
              Slider(
                value = sensitivitySlider,
                onValueChange = { sensitivitySlider = it },
                onValueChangeFinished = {
                  scope.launch {
                    ctx.appPrefsStore.updateData { prefs ->
                      prefs.copy { mouseSensitivity = sensitivitySlider }
                    }
                  }
                },
                valueRange = 0.25f..3.0f,
              )
              Text(
                "%.2fx".format(sensitivitySlider),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Row(
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              TextField(
                label = {
                  Text(
                    stringResource(R.string.settings_screen_screen_name_label)
                  )
                },
                singleLine = true,
                value = screenName,
                colors = textFieldColors,
                onValueChange = {
                  screenName = it
                  isDirty = true
                },
                placeholder = {
                  Text(
                    stringResource(
                      R.string.settings_screen_screen_name_placeholder
                    )
                  )
                },
                modifier =
                  Modifier.focusRequester(focusRequester)
                    .background(Color.Transparent)
                    .fillMaxWidth(),
              )
            }

            Row(
              horizontalArrangement =
                Arrangement.spacedBy(
                  8.dp
                ), // verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              TextField(
                value = address,
                maxLines = 1,
                label = {
                  Text(
                    stringResource(
                      R.string.settings_screen_screen_address_label
                    )
                  )
                },
                onValueChange = {
                  address = it
                  isDirty = true
                },
                placeholder = {
                  Text(
                    stringResource(
                      R.string.settings_screen_screen_address_placeholder
                    )
                  )
                },
                singleLine = true,
                colors = textFieldColors,
                modifier = Modifier.background(Color.Transparent).weight(1f),
              )

              TextField(
                value = port.toString(),
                colors = textFieldColors,
                label = {
                  Text(
                    stringResource(R.string.settings_screen_screen_port_label)
                  )
                },
                onValueChange = { newPort ->
                  if (newPort.all { it.isDigit() }) {
                    port = newPort.toInt()
                    isDirty = true
                  }
                },
                placeholder = {
                  Text(
                    stringResource(
                      R.string.settings_screen_screen_port_placeholder
                    )
                  )
                },
                keyboardOptions =
                  KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                  ),
                singleLine = true,
                modifier =
                  Modifier.background(Color.Transparent)
                    .width(textStyleCharWidthDp * 6f),
              )

              Column(
                verticalArrangement =
                  Arrangement.spacedBy(
                    space = 0.dp,
                    alignment = Alignment.CenterVertically,
                  ),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 2.dp),
              ) {
                Text(
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  text =
                    stringResource(R.string.app_prefs_screen_server_use_tls),
                )
                Checkbox(
                  checked = useTls,
                  onCheckedChange = {
                    useTls = it
                    isDirty = true
                  },
                )
              }
            }
          }
        }
      }
    }
  }
}

@Preview
@Composable
fun SettingsScreenPreviewLoading() {
  PreviewDeskflowThemedRoot {
    SettingsScreen(
      uiState = SettingsUiState.Loading,
      onChange = {},
      onCancel = {},
    )
  }
}

@PreviewAll
@Composable
fun SettingsScreenPreviewSuccess() {
  PreviewDeskflowThemedRoot { appState ->
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val snackbarHost = LocalSnackbarHostState.current

    var screenStateValue by remember {
      mutableStateOf(
        ScreenState().apply {
          name = "AndroidScreen"
          server =
            ServerState().apply {
              address = "localhost"
              port = 24800
              useTls = false
            }
        }
      )
    }
    SettingsScreen(
      uiState = SettingsUiState.Success(true, screenStateValue),
      onChange = { screenState ->
        screenStateValue = screenState
        scope.launch {
          snackbarHost.showSnackbar(ctx.getString(R.string.toast_settings_saved))
        }
      },
      onCancel = {},
    )
  }
}

sealed class SettingsUiState {
  data object Loading : SettingsUiState()

  data class Success(val isConnected: Boolean, val screen: ScreenState) :
    SettingsUiState()
}
