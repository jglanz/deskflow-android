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

package org.tfv.deskflow.services

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import arrow.core.raise.catch
import io.github.oshai.kotlinlogging.Level
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tfv.deskflow.BuildConfig
import org.tfv.deskflow.client.Client
import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.ext.toDebugString
import org.tfv.deskflow.client.manager.ClipboardSendManager
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.net.CertificateFingerprint
import org.tfv.deskflow.client.net.FingerprintVerificationCallback
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.data.aidl.ConnectionState
import org.tfv.deskflow.data.aidl.IConnectionService
import org.tfv.deskflow.data.aidl.IConnectionServiceCallback
import org.tfv.deskflow.data.aidl.Result
import org.tfv.deskflow.data.aidl.ScreenState
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.data.ClientCertificateManager
import org.tfv.deskflow.data.TrustStore
import org.tfv.deskflow.data.models.AppPrefs
import org.tfv.deskflow.data.models.AppPrefsKt.ScreenConfigKt.serverConfig
import org.tfv.deskflow.data.models.AppPrefsKt.loggingConfig
import org.tfv.deskflow.data.models.AppPrefsKt.screenConfig
import org.tfv.deskflow.data.models.AppPrefsKt.connectionConfig
import org.tfv.deskflow.data.models.copy
import org.tfv.deskflow.ext.copy
import org.tfv.deskflow.ext.getSerializableCompat
import org.tfv.deskflow.ext.toServerTarget
import org.tfv.deskflow.logging.AndroidForwardingLogger
import org.tfv.deskflow.logging.LogRecordEvent
import org.tfv.deskflow.receivers.ScreenStateReceiver
import org.tfv.deskflow.ui.models.FingerprintVerificationState

class ConnectionService : Service() {
  companion object {
    private val log = KLoggingManager.forwardingLogger<ConnectionService>()
  }

  private var connectionStateUpdateJob: Job? = null

  private val serviceScope =
    CoroutineScope(Dispatchers.Default + SupervisorJob())

  private val broadcastLock = Any()
  private var broadcastThread: Thread? = null
  private val broadcastThreadFactory = ThreadFactory { r ->
    synchronized(broadcastLock) {
      if (broadcastThread == null)
        broadcastThread = Thread(r, "BroadcastEventLoopThread")

      return@ThreadFactory broadcastThread
    }
  }
  private val broadcastExecutor =
    Executors.newSingleThreadExecutor(broadcastThreadFactory)

  private var fingerprintVerificationCallback: FingerprintVerificationCallback? = null

  private val clientCertificateManager: ClientCertificateManager by lazy {
    ClientCertificateManager(applicationContext)
  }

  @Volatile
  private var client: Client? = null

  private fun getOrCreateClient(): Client {
    return client ?: synchronized(this) {
      client ?: run {
        // Ensure certificate exists before creating client
        clientCertificateManager.ensureCertificateExists()
        val keyManager = clientCertificateManager.getKeyManager()
        Client(fingerprintVerificationCallback, keyManager).also {
          client = it
        }
      }
    }
  }

  private fun recreateClient() {
    synchronized(this) {
      val oldClient = client
      if (oldClient != null) {
        log.info { "Disposing old client" }
        oldClient.dispose()
      }
      client = null
      log.info { "Old client disposed, ready to create new one" }
      // Will be recreated on next connection attempt
    }
  }

  /** The connection state model */
  private lateinit var connectionStateModel: ConnectionStateModel

  /** Screen state receiver for monitoring screen on/off events */
  private var screenStateReceiver: ScreenStateReceiver? = null

  /** Flag to track if we should reconnect when screen turns back on */
  private var shouldReconnectOnScreenOn = false

  /** Flag to track if disconnect on screen off is enabled */
  private var disconnectOnScreenOff = false

  /** Job for delayed disconnect on screen off */
  private var screenOffDisconnectJob: Job? = null

  private val connectionCallbacks =
    RemoteCallbackList<IConnectionServiceCallback>()

  /**
   * Inner class that handles fingerprint verification challenges
   */
  private inner class FingerprintVerificationHandlerImpl : FingerprintVerificationCallback {
    override suspend fun onFingerprintChallenge(
      certificateFingerprint: CertificateFingerprint,
      callback: suspend (Boolean) -> Unit,
    ) {
      log.info { "Certificate fingerprint verification requested" }
      log.info { "Subject: ${certificateFingerprint.certificate.subjectX500Principal}" }
      log.info { "Fingerprint: ${certificateFingerprint.fingerprint}" }

      // Check if this fingerprint is already trusted
      val state = connectionStateModel.state.value
      val serverAddress = state.screen.server.address
      val serverPort = state.screen.server.port

      val trustStore = TrustStore(applicationContext)

      // Check what verification result we should show
      val storedFingerprint = trustStore.getTrustedFingerprint(serverAddress, serverPort)

      when {
        storedFingerprint == null -> {
          // Unknown fingerprint - first time seeing this server
          log.info { "Unknown fingerprint, requesting user verification" }
          val result = org.tfv.deskflow.client.net.FingerprintVerificationResult.Unknown(
            certificateFingerprint.certificate,
            certificateFingerprint.fingerprint,
            certificateFingerprint.clientAuthRequested
          )
          FingerprintVerificationState.getInstance().postChallenge(result) { accepted ->
            if (accepted) {
              log.info { "User accepted fingerprint, storing in TrustStore" }
              serviceScope.launch {
                trustStore.trustFingerprint(serverAddress, serverPort, certificateFingerprint.fingerprint)
              }
            } else {
              log.warn { "User rejected fingerprint" }
            }
            callback(accepted)
          }
        }

        storedFingerprint == certificateFingerprint.fingerprint -> {
          // Trusted - matches stored fingerprint
          log.info { "Fingerprint matches trusted value, accepting" }
          callback(true)
        }

        else -> {
          // Mismatch - server certificate changed!
          log.error { "Fingerprint MISMATCH! Expected: $storedFingerprint, Got: ${certificateFingerprint.fingerprint}" }
          val result = org.tfv.deskflow.client.net.FingerprintVerificationResult.Mismatch(
            certificateFingerprint.certificate,
            certificateFingerprint.fingerprint,
            storedFingerprint,
            certificateFingerprint.clientAuthRequested
          )
          FingerprintVerificationState.getInstance().postChallenge(result) { accepted ->
            if (accepted) {
              log.warn { "User accepted MISMATCHED fingerprint, updating TrustStore" }
              serviceScope.launch {
                trustStore.trustFingerprint(serverAddress, serverPort, certificateFingerprint.fingerprint)
              }
            } else {
              log.info { "User rejected mismatched fingerprint" }
            }
            callback(accepted)
          }
        }
      }
    }

    /**
     * Check if fingerprint was rejected and disable connection if needed
     */
    fun checkAndHandleRejection() {
      val state = FingerprintVerificationState.getInstance()
      if (state.isConnectionDisabledDueToRejection()) {
        log.info { "Fingerprint was rejected, disabling connection" }
        connectionStateModel.updateState { it.copy(isEnabled = false) }
        state.clear()
      }
    }
  }

  private val binder =
    object : IConnectionService.Stub() {

      override fun setClipboardData(bundle: Bundle): Result {
        return catch({
          // Don't create client if disabled
          if (!connectionStateModel.state.value.isEnabled) {
            return Result().apply {
              ok = false
              message = "Connection is disabled"
            }
          }

          val clipboardData = bundle.getSerializableCompat("clipboardData",ClipboardData::class.java)
          require(clipboardData != null) {
            "clipboardData was not valid in clipboard data bundle"
          }
          getOrCreateClient().sendClipboard(clipboardData)
          return Result().apply {
            ok = true
            message = ""
          }
        }) { err: Throwable ->
          log.error(err) { "Failed to set clipboard data" }
          return Result().apply {
            ok = false
            message = err.message
          }
        }

      }

      override fun regenerateClientCertificate(): Result {
        return catch({
          log.info { "Regenerating client certificate" }

          // Remember if we were enabled
          val wasEnabled = connectionStateModel.state.value.isEnabled

          // Disable connection first to stop any reconnection attempts
          if (wasEnabled) {
            log.info { "Disabling connection before regenerating certificate" }
            connectionStateModel.updateState { it.copy(isEnabled = false) }
          }

          // Regenerate the certificate
          clientCertificateManager.generateCertificate()

          // Recreate the client to use the new certificate
          // This properly shuts down the old client's executor thread
          recreateClient()

          // Re-enable connection if it was enabled before
          if (wasEnabled) {
            log.info { "Re-enabling connection with new certificate" }
            connectionStateModel.updateState { it.copy(isEnabled = true) }
          }

          log.info { "Client certificate regenerated and client restarted" }

          Result().apply {
            ok = true
            message = "Client certificate regenerated successfully"
          }
        }) { err: Throwable ->
          log.error(err) { "Failed to regenerate client certificate" }
          Result().apply {
            ok = false
            message = err.message ?: "Failed to regenerate certificate"
          }
        }
      }

      override fun setEnabled(enabled: Boolean): Result {
        // Persist the enabled state to preferences
        runBlocking {
          appPrefsStore.updateData { appPrefs ->
            appPrefs.copy {
              connection = connectionConfig {
                isEnabled = enabled
              }
            }
          }
        }

        // Dispose client when disabling to save battery
        if (!enabled) {
          // Update state first so client can disconnect cleanly
          connectionStateModel.updateState {
            it.copy(
              isEnabled = false,
              isConnected = false,
              ackReceived = false
            )
          }

          // Give the client a moment to process the disable and disconnect
          Thread.sleep(100)

          synchronized(this@ConnectionService) {
            client?.let {
              log.info { "Disposing client due to disable" }
              it.dispose()
              client = null
            }
          }
        } else {
          connectionStateModel.updateState { it.copy(isEnabled = true) }
          // Client will be recreated on next checkEnabled() call
          log.info { "Client will be recreated on next connection attempt" }
        }

        return Result().apply {
          ok = true
          message = ""
        }
      }

      override fun setCaptureKeyMode(enabled: Boolean): Result {
        connectionStateModel.updateState {
          it.copy(isCaptureKeyModeEnabled = enabled)
        }
        return Result().apply {
          ok = true
          message = ""
        }
      }

      override fun setLogForwardingLevel(levelName: String?): Result {
        val result =
          Result().apply {
            ok = false
            message = ""
          }
        if (levelName != null) {
          catch({
            val level = Level.valueOf(levelName)
            runBlocking {
              appPrefsStore.updateData { appPrefs ->
                appPrefs.copy {
                  loggingConfig {
                    forwardingLevel = level.name
                    forwardingEnabled = true
                  }
                }
              }
            }
            AndroidForwardingLogger.forwardingLevel = level
            result.ok = true
          }) { err: Throwable ->
            log.error(err) { "Invalid log level: $levelName" }
            result.message = err.message
          }
        } else {
          result.message = "levelName is null"
        }

        return result
      }

      override fun registerCallback(callback: IConnectionServiceCallback?) {
        if (callback != null) connectionCallbacks.register(callback)
      }

      override fun unregisterCallback(callback: IConnectionServiceCallback?) {
        if (callback != null) connectionCallbacks.unregister(callback)
      }

      override fun updateScreenState(screenState: ScreenState?): Result {
        val res = Result()
        if (screenState == null)
          return res.apply {
            ok = false
            message = "screenState is null"
          }
        runBlocking {
          appPrefsStore.updateData { appPrefs ->
            return@updateData appPrefs
              .toBuilder()
              .apply {
                screen = screenConfig {
                  name = screenState.name
                  disconnectOnScreenOff = screenState.disconnectOnScreenOff
                  server = serverConfig {
                    address = screenState.server.address
                    port = screenState.server.port
                    useTls = screenState.server.useTls
                  }
                }
              }
              .build()
          }
        }

        return res.apply { ok = true }
      }

      override fun updateScreenDimensions(width: Int, height: Int): Result {
        val res = Result()
        if (width <= 0 || height <= 0) {
          return res.apply {
            ok = false
            message = "Invalid screen dimensions: ${width}x${height}"
          }
        }

        log.info { "Updating screen dimensions to ${width}x${height}" }
        connectionStateModel.updateScreenDimensions(width, height)

        // The connectionStateUpdateJobRunnable will automatically update the client's target
        // with the new dimensions when the state flow emits the change.
        // The MessageHandler will then use the updated dimensions when the server queries for info.

        return res.apply { ok = true }
      }

      override fun getState(): ConnectionState {
        // state is already a StateFlow; read it directly. stateIn(serviceScope)
        // launched a new never-ending sharing coroutine on every call.
        return connectionStateModel.state.value
      }
    }

  override fun onBind(intent: Intent?): IBinder {
    return binder
  }

  /** Event handler for `ClientEventBus` events. */
  private fun onClientEvent(event: ClientEvent) {
    when (event) {
      is ScreenEvent.AckReceived -> {
        connectionStateModel.updateState { it.copy(ackReceived = true) }
      }
      is ScreenEvent.Enter -> {
        connectionStateModel.updateState {
          it.copy(screen = it.screen.copy(isActive = true))
        }
      }
      is ScreenEvent.Leave -> {
        connectionStateModel.updateState {
          it.copy(screen = it.screen.copy(isActive = false))
        }
      }
      is ConnectionEvent -> {
        val connected = event is ConnectionEvent.Connected
        log.debug { "ConnectionEvent(connected=$connected)" }
        connectionStateModel.updateState {
          it.copy(
            isConnected = connected,
            ackReceived = if (connected) it.ackReceived else false,
            screen = it.screen.copy(isActive = false),
          )
        }
      }
      is LogRecordEvent,
      is ScreenEvent.SetClipboard,
      is MouseEvent,
      is KeyboardEvent -> {
        val bundle =
          Bundle().apply {
            putString("type", event.javaClass.name)
            putSerializable("message", event)
          }

        sendToClients { onMessage(bundle) }
      }
    }
  }

  /** Check if the client is enabled and update if necessary. */
  private fun checkEnabled() {
    val state = connectionStateModel.state.value

    // Only create/get client if enabled, otherwise we'd recreate what we just disposed
    if (!state.isEnabled) {
      log.debug { "Connection is disabled, skipping client check" }
      return
    }

    val client = getOrCreateClient()

    if (state.isEnabled != client.isEnabled) {
      log.info {
        "ConnectionService setting Client.isEnabled=${state.isEnabled}"
      }
      client.setEnabled(state.isEnabled)
    }
  }

  /**
   * Register the screen state receiver to monitor screen on/off events.
   */
  private fun registerScreenStateReceiver() {
    if (screenStateReceiver != null) {
      return // Already registered
    }

    log.info { "Registering screen state receiver" }

    screenStateReceiver = ScreenStateReceiver(
      onScreenOn = { handleScreenOn() },
      onScreenOff = { handleScreenOff() }
    )

    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_SCREEN_OFF)
    }

    registerReceiver(screenStateReceiver, filter)
  }

  /**
   * Unregister the screen state receiver.
   */
  private fun unregisterScreenStateReceiver() {
    screenStateReceiver?.let {
      log.info { "Unregistering screen state receiver" }
      try {
        unregisterReceiver(it)
      } catch (e: IllegalArgumentException) {
        log.warn(e) { "Screen state receiver was not registered" }
      }
      screenStateReceiver = null
    }
  }

  /**
   * Handle screen turning on.
   * Reconnect if we were previously connected before screen turned off.
   * Cancel any pending disconnect job.
   */
  private fun handleScreenOn() {
    log.info { "Screen turned ON - shouldReconnect=$shouldReconnectOnScreenOn" }

    // Cancel any pending disconnect job if screen turned back on
    screenOffDisconnectJob?.cancel()
    screenOffDisconnectJob = null

    if (shouldReconnectOnScreenOn) {
      log.info { "Reconnecting after screen turned on" }
      connectionStateModel.updateState { it.copy(isEnabled = true) }
      // Client will be recreated on next checkEnabled() call
      log.info { "Client will be recreated on next connection attempt" }
      shouldReconnectOnScreenOn = false
    }
  }

  /**
   * Handle screen turning off.
   * Disconnect if the feature is enabled and we're currently connected,
   * with a small delay to allow for wakeup with for example mouse movement.
   */
  private fun handleScreenOff() {
    val state = connectionStateModel.state.value
    log.info { "Screen turned OFF - isEnabled=${state.isEnabled}, isConnected=${state.isConnected}" }

    if (disconnectOnScreenOff && state.isEnabled) {
      log.info { "Screen off - will disconnect in 10 seconds" }

      // Cancel any existing pending disconnect job
      screenOffDisconnectJob?.cancel()

      // Schedule disconnect with a delay
      screenOffDisconnectJob = serviceScope.launch {
        try {
          delay(10000)
          log.info { "Disconnecting due to screen off" }
          shouldReconnectOnScreenOn = true

          // Update state first so client can disconnect cleanly
          connectionStateModel.updateState {
            it.copy(
              isEnabled = false,
              isConnected = false,
              ackReceived = false
            )
          }

          // Give the client a moment to process the disable and disconnect
          delay(100)

          // Dispose client to save battery
          synchronized(this@ConnectionService) {
            client?.let {
              log.info { "Disposing client due to screen off" }
              it.dispose()
              client = null
            }
          }
        } catch (e: Exception) {
          log.debug(e) { "Screen off disconnect job cancelled or failed" }
        }
      }
    }
  }

  /** Check if the client is connected and update if necessary. */
  private suspend fun connectionStateUpdateJobRunnable() {
    connectionStateModel.state.collect { state ->
      log.info { "CHANGED: ConnectionState(${state.toDebugString()})" }

      // Check if fingerprint was rejected
      fingerprintVerificationCallback?.let { handler ->
        if (handler is FingerprintVerificationHandlerImpl) {
          handler.checkAndHandleRejection()
        }
      }

      checkEnabled()
      sendToClients { onStateChanged(state) }

      // Only set target if client exists and is enabled
      if (state.isEnabled) {
        val screen = state.screen
        getOrCreateClient().setTarget(screen.toServerTarget())
      }
    }
  }

  /**
   * Update the service configuration when preferences
   *
   * This is attached to the `appPrefsStore` flow and will be called
   */
  private val onAppPrefsChanged = FlowCollector { appPrefs: AppPrefs ->
    appPrefs.logging?.let { logging ->
// TODO: Add toggle to settings to enable/disable forwarding
//      logging.forwardingEnabled
      AndroidForwardingLogger.setForwardingEnabled(true)
      val forwardingLevel =
        logging.forwardingLevel.uppercase().let { levelName ->
          Level.entries.find { level -> level.name == levelName } ?: Level.INFO
        }

      AndroidForwardingLogger.forwardingLevel = forwardingLevel

      log.info {
        "AppPrefs changed: forwardingLevel=${AndroidForwardingLogger.forwardingLevel}"
      }
    }

    // Handle disconnect on screen off setting
    appPrefs.screen?.let { screen ->
      val newDisconnectOnScreenOff = screen.disconnectOnScreenOff

      if (newDisconnectOnScreenOff != disconnectOnScreenOff) {
        log.info { "Disconnect on screen off changed: $disconnectOnScreenOff -> $newDisconnectOnScreenOff" }
        disconnectOnScreenOff = newDisconnectOnScreenOff

        if (disconnectOnScreenOff) {
          registerScreenStateReceiver()
        } else {
          unregisterScreenStateReceiver()
          shouldReconnectOnScreenOn = false
        }
      }
    }

    connectionStateModel.updateScreenFromAppPrefs(appPrefs)
  }

  override fun onCreate() {
    super.onCreate()
    log.debug { "onCreate:${this::class.java.simpleName}" }

//    if (BuildConfig.DEBUG) {
//      android.os.Debug.waitForDebugger()
//    }

    // Initialize fingerprint verification handler
    fingerprintVerificationCallback = FingerprintVerificationHandlerImpl()

    connectionStateModel = ConnectionStateModel(this)

    // Restore state from preferences synchronously before starting the state update job
    runBlocking {
      val appPrefs = appPrefsStore.data.first()
      connectionStateModel.updateScreenFromAppPrefs(appPrefs)

      // Restore the isEnabled state from preferences
      val savedIsEnabled = if (appPrefs.hasConnection()) {
        appPrefs.connection.isEnabled
      } else {
        true // Default to enabled on first install
      }
      connectionStateModel.updateState { it.copy(isEnabled = savedIsEnabled) }
    }

    ClientEventBus.on(this::onClientEvent)

    // Start the state update job after restoring the state
    connectionStateUpdateJob =
      serviceScope.launch { connectionStateUpdateJobRunnable() }

    // Observe preference changes
    serviceScope.launch {
      appPrefsStore.data.collect(onAppPrefsChanged)
    }
  }

  /**
   * Send a message to all registered clients.
   *
   * @param fn The function to call on each client.
   */
  private fun sendToClients(fn: IConnectionServiceCallback.() -> Unit) {
    if (broadcastExecutor.isShutdown) return

    broadcastExecutor.submit {
      synchronized(broadcastLock) {
        try {
          val count = connectionCallbacks.beginBroadcast()
          try {
            for (i in 0 until count) {
              try {
                connectionCallbacks.getBroadcastItem(i).fn()
              } catch (e: Exception) {
                log.error(e) { "Error sending to client" }
              }
            }
          } finally {
            connectionCallbacks.finishBroadcast()
          }
        } catch (err: Exception) {
          log.error(err) { "Error sendingToClients" }
        }
      }
    }
  }

  override fun onDestroy() {
    ClientEventBus.off(this::onClientEvent)

    // Unregister screen state receiver if registered
    unregisterScreenStateReceiver()

    // Cancel any pending disconnect job
    screenOffDisconnectJob?.cancel()
    screenOffDisconnectJob = null

    synchronized(broadcastLock) {
      broadcastExecutor.shutdownNow()
      connectionCallbacks.kill()
    }

    client?.dispose()

    connectionStateUpdateJob?.cancel()
    connectionStateUpdateJob = null

    serviceScope.cancel()

    super.onDestroy()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }
}
