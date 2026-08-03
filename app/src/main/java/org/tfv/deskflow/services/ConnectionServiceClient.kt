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

@file:OptIn(ExperimentalAtomicApi::class, ExperimentalAtomicApi::class,
  ExperimentalAtomicApi::class, ExperimentalAtomicApi::class
)

package org.tfv.deskflow.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.ext.toDebugString
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.data.aidl.ConnectionState
import org.tfv.deskflow.data.aidl.IConnectionService
import org.tfv.deskflow.data.aidl.IConnectionServiceCallback
import org.tfv.deskflow.data.aidl.Result
import org.tfv.deskflow.data.aidl.ScreenState
import org.tfv.deskflow.data.aidl.ServerState
import org.tfv.deskflow.ext.ConnectionStateDefaults
import org.tfv.deskflow.ext.copy
import org.tfv.deskflow.logging.LogRecordEvent
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class ConnectionServiceClient(
  private val ctx: Context,
  private val onClientEvent: ((ClientEvent) -> Unit)? = null,
) : IConnectionService, AbstractDisposable() {
  companion object {
    private val log = KLoggingManager.logger(ConnectionServiceClient::class)
  }

  private val stateEditableFlow = MutableStateFlow(ConnectionStateDefaults(ctx))
  private val logRecordsEditableFlow = MutableStateFlow(emptyList<LogRecordEvent>())

  private val isBoundStorage = AtomicBoolean(false)

  val isBound: Boolean
    get() = isBoundStorage.load()

  override fun setEnabled(enabled: Boolean): Result {
    return connectionService?.setEnabled(enabled) ?: notBoundResult()
  }

  private fun notBoundResult(): Result {
    return Result().apply {
      ok = false
      message = "Connection service is not bound"
    }
  }

  val stateFlow: StateFlow<ConnectionState> = stateEditableFlow.asStateFlow()

  val logRecordsFlow: StateFlow<List<LogRecordEvent>> = logRecordsEditableFlow.asStateFlow()

  fun addLogRecord(logRecord: LogRecordEvent) {
    logRecordsEditableFlow.update { current ->
      when {
        current.isEmpty() -> listOf(logRecord)
        current.size > 1000 -> current.subList(1, current.size) + logRecord
        else -> current + logRecord
      }
    }
  }

  override fun setClipboardData(bundle: Bundle): Result {
    return connectionService?.setClipboardData(bundle) ?: Result().apply {
      ok = false
      message = "Connection service client is null"
    }
  }

  private val connectionServiceCallback = object : IConnectionServiceCallback.Stub() {

    override fun onStateChanged(state: ConnectionState?) {
      log.debug { "connectionServiceCallback.onStateChanged: ${state?.toDebugString()}" }
      if (state != null) {
        stateEditableFlow.update { state }
      }

    }



    override fun onMessage(bundle: Bundle?) {
      try {
        if (bundle == null) {
          log.error { "Bundle is null" }
          return
        }

        bundle.classLoader = ctx.classLoader

        val clazzName = bundle.getString("type")
        require(bundle.containsKey("message") && clazzName != null) {
          "Bundle must contain a 'message' parameter & type cannot be null ($clazzName)"
        }
        val message = when (clazzName) {
          LogRecordEvent::class.java.name -> {
            val logRecord = bundle.getSerializable("message", LogRecordEvent::class.java)!!
            addLogRecord(logRecord)

            return
          }

          ScreenEvent.SetClipboard::class.java.name -> {
            bundle.getSerializable("message", ScreenEvent.SetClipboard::class.java)
          }

          KeyboardEvent::class.java.name -> {
            bundle.getSerializable("message", KeyboardEvent::class.java)
          }

          MouseEvent::class.java.name -> {
            bundle.getSerializable("message", MouseEvent::class.java)
          }

          else -> {
            log.warn { "$clazzName not supported yet" }
            return
          }
        }

        require(message != null) {
          "Bundle must contain a 'message' parameter & type cannot be null ($clazzName)"
        }

        log.debug { "onMessage($clazzName) -> ClientEventBus: $message" }
        onClientEvent?.invoke(message)

      } catch (err: Exception) {
        log.error(err) { "onMessage: ${err.message}" }
      }
    }
  }

  private var connectionService: IConnectionService? = null

  /**
   * Holds the underlying service connection to the `ConnectionService`
   */
  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      connectionService = IConnectionService.Stub.asInterface(service)
      connectionService?.registerCallback(connectionServiceCallback)

      connectionService?.state?.let {
        log.debug { "Initial connection state: $it" }
        stateEditableFlow.value = it
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      connectionService = null
      stateEditableFlow.update { current ->
        current.copy(
          isConnected = false,
          ackReceived = false,
          screen = current.screen.copy(isActive = false)
        )
      }
    }
  }

  fun bind(): Boolean {
    synchronized(this) {
      if (isBound)
        return true

      val serviceClazz = ConnectionService::class.java
      val intent = Intent(serviceClazz.name)
      intent.setPackage(ctx.packageName)
      log.debug { "bindService: $intent" }
      isBoundStorage.store(ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE))

      log.debug { "bindService: bound=$isBound" }
      return isBound
    }

  }

  fun unbind() {
    synchronized(this) {

      val connectionService = connectionService
      if (connectionService != null) {
        connectionService.unregisterCallback(connectionServiceCallback)
        ctx.unbindService(serviceConnection)
      }
      this.connectionService = null
      isBoundStorage.store(false)
    }
  }

  override fun setLogForwardingLevel(levelName: String?): Result {
    return connectionService?.setLogForwardingLevel(levelName) ?: Result().apply {
      ok = false
      message = "Connection service is not bound"
    }
  }

  override fun setCaptureKeyMode(enabled: Boolean): Result {
    return connectionService?.setCaptureKeyMode(enabled) ?: Result().apply {
      ok = false
      message = "Connection service is not bound"
    }
  }

  override fun registerCallback(callback: IConnectionServiceCallback?) {
    connectionService?.registerCallback(callback)
  }

  override fun unregisterCallback(callback: IConnectionServiceCallback?) {
    connectionService?.unregisterCallback(callback)
  }

  override fun updateScreenState(screenState: ScreenState?): Result? {
    return connectionService?.updateScreenState(screenState)
  }

  override fun getState(): ConnectionState {
    val state = connectionService?.state ?: ConnectionState()
    stateEditableFlow.value = state
    return state
  }

  override fun asBinder(): IBinder? {
    return null
  }

  override fun onDispose() {
    unbind()
  }

  fun clearLogRecords() {
    logRecordsEditableFlow.value = emptyList()
  }
}