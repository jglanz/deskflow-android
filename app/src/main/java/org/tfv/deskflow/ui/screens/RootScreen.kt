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

@file:OptIn(ExperimentalLayoutApi::class, ExperimentalPermissionsApi::class)

package org.tfv.deskflow.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.tfv.deskflow.R
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.ext.canDrawOverlays
import org.tfv.deskflow.ext.isAccessibilityServiceEnabled
import org.tfv.deskflow.ext.isInputMethodServiceEnabled
import org.tfv.deskflow.ext.launchInputMethodServiceSettings
import org.tfv.deskflow.ext.observeAccessibilityStatus
import org.tfv.deskflow.ext.requestAccessibilityEnabled
import org.tfv.deskflow.ext.requestOverlayPermission
import org.tfv.deskflow.ext.registerForServiceDisconnectionEvents
import org.tfv.deskflow.services.GlobalInputService
import org.tfv.deskflow.ui.annotations.PreviewAll
import org.tfv.deskflow.ui.components.AppState
import org.tfv.deskflow.ui.components.AppToolbar
import org.tfv.deskflow.ui.components.IAppState
import org.tfv.deskflow.ui.components.LifecycleEventHookEffect
import org.tfv.deskflow.ui.components.LocalAppState
import org.tfv.deskflow.ui.components.LocalSnackbarHostState
import org.tfv.deskflow.ui.components.PermissionsNeededDialog
import org.tfv.deskflow.ui.components.AccessibilityServiceRestartDialog
import org.tfv.deskflow.ui.components.RootNavHost
import org.tfv.deskflow.ui.components.FingerprintVerificationDialog
import org.tfv.deskflow.ui.models.FingerprintVerificationState
import org.tfv.deskflow.ui.components.currentDeviceConfig
import org.tfv.deskflow.data.ClientCertificateManager
import org.tfv.deskflow.client.net.FingerprintManager
import org.tfv.deskflow.ui.components.preview.PreviewAppState
import org.tfv.deskflow.ui.theme.DeskflowTheme
import org.tfv.deskflow.ui.theme.LocalDeskflowExtendedColorScheme

private val log = KLoggingManager.logger("RootScreen")

@Composable
fun RootScreenRoute(appState: IAppState) {
  RootScreen(appState = appState)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(appState: IAppState) {
  val context = LocalContext.current
  val (isPortrait, isXL, isLarge) = currentDeviceConfig()
  val scope = rememberCoroutineScope()

  UpdatePermissionsEffect(appState = appState)
  val snackbarHostState = remember { SnackbarHostState() }

  // Track if we should show the accessibility restart dialog
  val showAccessibilityRestartDialog = remember { mutableStateOf(false) }

  // Listen for accessibility service status changes
  DisposableEffect(Unit) {
    val closeChecker = context.observeAccessibilityStatus(GlobalInputService::class.java, scope = scope) { isEnabled ->
      val isServiceEnabled = context.isAccessibilityServiceEnabled(GlobalInputService::class.java)
      log.info { "Accessibility service enabled: isEnabled=$isEnabled,isServiceEnabled=$isServiceEnabled" }

      appState.updatePermissions(
        canDrawOverlays = context.canDrawOverlays(),
        accessibilityEnabled = isServiceEnabled,
        imeEnabled = context.isInputMethodServiceEnabled(),
      )

      if (isServiceEnabled)
        context.startService(Intent(context, GlobalInputService::class.java))
    }

    onDispose { closeChecker() }
  }

  // Listen for service disconnection events (crashes/unbinds while still enabled)
  DisposableEffect(Unit) {
    val disconnectionListener = context.registerForServiceDisconnectionEvents(
      GlobalInputService::class
    ) {
      log.warn { "Accessibility service disconnected unexpectedly" }
      // Check if service is still enabled in settings but not actually running
      val isStillEnabledInSettings = context.isAccessibilityServiceEnabled(GlobalInputService::class.java)
      if (isStillEnabledInSettings) {
        log.warn { "Service is enabled in settings but not running - showing restart dialog" }
        showAccessibilityRestartDialog.value = true
      }
    }

    onDispose { disconnectionListener.close() }
  }

  CompositionLocalProvider(
    LocalSnackbarHostState provides snackbarHostState,
    LocalAppState provides appState,
  ) {
    DeskflowTheme {
      val extColorScheme = LocalDeskflowExtendedColorScheme.current
      Surface(
        color = extColorScheme.toolbar,
        modifier = Modifier.fillMaxSize(),
      ) {
        Scaffold(
          snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
          topBar = { AppToolbar() },
          bottomBar = {},
          modifier =
            Modifier.windowInsetsPadding(WindowInsets.Companion.statusBars)
              .imePadding(),
        ) { innerPadding ->

          // COMPLEX PERMISSIONS
          val imeEnabled by
            appState.permissionIMEEnabled.collectAsStateWithLifecycle()
          val canDrawOverlays by
            appState.permissionCanDrawOverlays.collectAsStateWithLifecycle()
          val accessibilityEnabled by
            appState.permissionAccessibilityEnabled
              .collectAsStateWithLifecycle()

          // REGULAR PERMISSIONS
          //
          // Both are API 33+. Below that the request resolves to "denied"
          // instantly and would pin the user on an unsatisfiable dialog.
          val needsApi33Permissions =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
          val notificationsPermissionState =
            rememberPermissionState(
              android.Manifest.permission.POST_NOTIFICATIONS
            )
          val nearbyDevicesPermissionState =
            rememberPermissionState(
              android.Manifest.permission.NEARBY_WIFI_DEVICES
            )

          if (!canDrawOverlays) {
            PermissionsNeededDialog(
              text = R.string.permission_dialog_overlay_message,
              onClick = { context.requestOverlayPermission() },
            )
          } else if (!accessibilityEnabled) {
            PermissionsNeededDialog(
              text = R.string.permission_dialog_accessibility_service_message,
              onClick = { context.requestAccessibilityEnabled() },
            )
          } else if (!imeEnabled) {
            PermissionsNeededDialog(
              text = R.string.permission_dialog_ime_enabled_message,
              onClick = { context.launchInputMethodServiceSettings() },
            )
          } else if (
            needsApi33Permissions && !notificationsPermissionState.status.isGranted
          ) {
            PermissionsNeededDialog(
              text = R.string.permission_dialog_notifications_message,
              required = false,
              onClick = {
                notificationsPermissionState.launchPermissionRequest()
              },
            )
          } else if (
            needsApi33Permissions && !nearbyDevicesPermissionState.status.isGranted
          ) {
            PermissionsNeededDialog(
              text = R.string.permission_dialog_nearby_devices_message,
              onClick = {
                nearbyDevicesPermissionState.launchPermissionRequest()
              },
            )
          }

          RootNavHost(
            appState = appState,
            modifier = Modifier.padding(innerPadding).fillMaxHeight(),
          )
        }
      }
    }
  }

  // Fingerprint verification dialog - shown on main screen
  val fingerprintVerificationState = FingerprintVerificationState.getInstance()
  val pendingVerification = fingerprintVerificationState.pendingVerification.collectAsState()

  pendingVerification.value?.let { result ->
    // Get client certificate fingerprint
    val certManager = ClientCertificateManager(context)
    val clientCert = certManager.getCertificate()
    val clientFingerprint = clientCert?.let { FingerprintManager.computeFingerprint(it) }

    FingerprintVerificationDialog(
      result = result,
      clientCertificateFingerprint = clientFingerprint,
      onAccept = {
        scope.launch {
          fingerprintVerificationState.acceptFingerprint()
        }
      },
      onReject = {
        scope.launch {
          fingerprintVerificationState.rejectFingerprint()
        }
      },
    )
  }

  // Accessibility service restart dialog - shown when service crashes/disconnects
  if (showAccessibilityRestartDialog.value) {
    AccessibilityServiceRestartDialog(
      onOpenSettings = {
        context.requestAccessibilityEnabled()
      },
      onDismiss = {
        showAccessibilityRestartDialog.value = false
      }
    )
  }
}

private fun updatePermissions(
  context: Context,
  appState: IAppState,
  permissionsGranted: MutableState<Boolean>,
) {
  val canDrawOverlays = context.canDrawOverlays()
  val imeEnabled = context.isInputMethodServiceEnabled()
  val isAccessibilityEnabled =
    context.isAccessibilityServiceEnabled(GlobalInputService::class.java)
  log.info {
    "updatePermissions(canDrawOverlays=$canDrawOverlays,isAccessibilityEnabled=$isAccessibilityEnabled)"
  }
  permissionsGranted.value =
    appState.updatePermissions(
      canDrawOverlays,
      isAccessibilityEnabled,
      imeEnabled,
    )
}

@VisibleForTesting()
@Composable
internal fun UpdatePermissionsEffect(
  appState: IAppState = LocalAppState.current
) {
  if (appState !is AppState) return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val permissionsGranted = remember { mutableStateOf(false) }
  val job = remember { mutableStateOf<Job?>(null) }

  DisposableEffect(Unit) {
    job.value =
      scope.launch {
        while (!permissionsGranted.value && job.value?.isActive == true) {
          log.info { "Checking permissions..." }
          updatePermissions(context, appState, permissionsGranted)
          delay(1000L)
        }
      }

    onDispose { job.value?.cancel() }
  }

  LifecycleEventHookEffect(Lifecycle.Event.ON_RESUME, fireImmediate = true) {
    updatePermissions(context, appState, permissionsGranted)
  }
}

@PreviewAll
@Composable
fun RootScreenRoutePreviewTablet() {
  PreviewAppState { appState -> RootScreenRoute(appState = appState) }
}
