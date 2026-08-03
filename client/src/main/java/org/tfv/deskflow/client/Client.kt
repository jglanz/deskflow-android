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

package org.tfv.deskflow.client

import arrow.core.raise.catch
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.events.MessagesEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.io.MessageParser
import org.tfv.deskflow.client.manager.ClipboardSendManager
import org.tfv.deskflow.client.manager.MessageHandler
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.models.SERVER_DEFAULT_ADDRESS
import org.tfv.deskflow.client.models.ServerTarget
import org.tfv.deskflow.client.net.FullDuplexSocket
import org.tfv.deskflow.client.net.tls.ClientCertificateProvider
import org.tfv.deskflow.client.net.tls.ServerTrustStore
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.SingletonThreadExecutor
import org.tfv.deskflow.client.util.disposeOf
import org.tfv.deskflow.client.util.logging.KLoggingManager

class Client(
  private val trustStore: ServerTrustStore? = null,
  private val clientCertProvider: ClientCertificateProvider? = null,
) : AbstractDisposable() {

  @Volatile private var socket: FullDuplexSocket? = null

  var isEnabled: Boolean = true
    private set

  var target: ServerTarget? = null
    private set

  var isScreenActive: Boolean = false
    private set

  var ackReceived: Boolean = false
    private set

  private val connectionExecutor =
    SingletonThreadExecutor("ConnectionEventLoopThread")

  init {
    ClientEventBus.on(this::onClientEvent)
    scheduleConnectionCheck()
  }

  /**
   * Enables or disables the client asynchronously.
   *
   * Submits the change to [connectionExecutor] to ensure thread safety. No
   * action is taken if the client is already in the desired state.
   *
   * @param enabled true to enable the client; false to disable it.
   */
  fun setEnabled(enabled: Boolean) {
    connectionExecutor.submit {
      if (isEnabled == enabled) return@submit

      isEnabled = enabled
    }
  }

  private fun scheduleConnectionCheck() {
    connectionExecutor.scheduleWithFixedDelay(
      delay = 1000L,
      runnable = {
        if (isDisposed) return@scheduleWithFixedDelay
        catch({
          when {
            !isEnabled -> {
              log.info { "Connection is disabled" }
              if (socket != null) {
                log.info { "Disconnecting due to disabled state" }
                disconnect()
              }
            }
            isConnected -> {
              log.debug { "Socket is connected already" }
            }
            else -> {
              log.debug { "Attempting to connect..." }
              connect()
            }
          }
        }) { err ->
          log.error(err) { "Error during connection check: ${err.message}" }
        }
      },
    )
  }

  private val messageParser = MessageParser()
  @Volatile private var messageHandler: MessageHandler? = null

  val isConnected: Boolean
    get() = socket?.isConnected ?: false

  fun setTarget(target: ServerTarget) {
    connectionExecutor.submit {
      log.info { "Setting target to ${target.address}:${target.port}" }
      val currentTarget = this.target
      if (target == currentTarget) {
        log.warn { "Targets are equal, no change" }
        return@submit
      }

      val address = target.address
      val port = target.port
      if (
        address == SERVER_DEFAULT_ADDRESS ||
          address.isBlank() ||
          port < 1 ||
          port > Short.MAX_VALUE.toInt()
      ) {
        log.warn { "Target is invalid, not updating" }
        return@submit
      }

      this.target = target

      connect()
    }
  }

  private fun connect() {
    if (isDisposed) return
    if (!connectionExecutor.isExecutorThread) {
      log.warn { "Connect called from non-executor thread, scheduling" }
      connectionExecutor.submit { connect() }
      return
    }

    disconnect()

    if (!isEnabled) {
      log.warn { "Client is not enabled, cannot connect" }
      return
    }
    val target = this.target
    if (target == null) {
      log.warn { "No valid target" }
      return
    }

    try {
      val socket =
        FullDuplexSocket(
          target.address,
          target.port,
          target.useTls,
          trustStore,
          clientCertProvider,
        )
      messageHandler = MessageHandler(socket, target)

      this.socket = socket
      socket.on { event ->
        when (event) {
          is FullDuplexSocket.SocketEvent.ConnectEvent -> {
            log.info { "ConnectEvent -> ConnectionEvent.Connected" }
            ClientEventBus.emit(ConnectionEvent.Connected)
          }

          is FullDuplexSocket.SocketEvent.DisconnectEvent -> {
            log.info { "DisconnectEvent -> ConnectionEvent.Disconnected" }
            connectionExecutor.submit { disconnect() }
            ClientEventBus.emit(ConnectionEvent.Disconnected)
          }

          is FullDuplexSocket.SocketEvent.ReceiveEvent -> {
            onReceiveEvent(event)
          }

          is FullDuplexSocket.SocketEvent.ErrorEvent -> {
            connectionExecutor.submit { disconnect() }
          }
        }
      }

      socket.start()

      log.debug { "Started socket" }
    } catch (err: Exception) {
      log.error(err) { "Unable to cleanly connect: ${err.message}" }
      disconnect()
    }
  }

  private fun onClientEvent(event: ClientEvent) {
    when (event) {
      is ScreenEvent.AckReceived -> {
        ackReceived = true
      }
      is ScreenEvent.Enter -> {
        isScreenActive = true
      }
      is ScreenEvent.Leave -> {
        isScreenActive = false
      }
      is ConnectionEvent.Disconnected -> {
        ackReceived = false
        isScreenActive = false
      }
    }
  }

  /** Socket receive handler */
  private fun onReceiveEvent(event: FullDuplexSocket.SocketEvent.ReceiveEvent) {
    val buf = event.buf
    log.trace { "ReceiveEvent(size=${buf.size()})" }
    val messages = messageParser.parseBuffer(buf)
    log.trace { "Parsed ${messages.size} messages" }
    if (messages.isNotEmpty()) {
      ClientEventBus.emit(MessagesEvent(messages))
    }
  }

  /**
   * Send clipboard data to server
   */
  fun sendClipboard(clipboardData: ClipboardData) {
    val messageHandler = messageHandler
    if (messageHandler == null) {
      log.info { "messageHandler == null, can not send clipboard" }
      return
    }

    ClipboardSendManager.sendClipboard(messageHandler, clipboardData)
  }

  /** Cleanup */
  override fun onDispose() {
    // isDisposed is already true here (AbstractDisposable.dispose sets it before
    // calling onDispose), so connect()/scheduleConnectionCheck bail out. Dispose
    // the socket + handler directly (disconnect() only re-submits to the executor
    // when called off-thread, which dispose always is) and shut the recurring
    // connection-check executor down so the Client frees its thread instead of
    // leaking it and reconnecting forever.
    //
    // The socket stop (thread.join) and MessageHandler teardown
    // (awaitTermination) can block for seconds, and ConnectionService.onDestroy
    // invokes this on the MAIN thread — so run that heavy work on a daemon
    // thread and return promptly (no ANR). Client is being discarded, so async
    // teardown is safe; isDisposed already guards all writers.
    val socketRef = socket
    val handlerRef = messageHandler
    socket = null
    messageHandler = null
    Thread(
        {
          disposeOf(socketRef)
          disposeOf(handlerRef)
          connectionExecutor.shutdown()
        },
        "DeskflowClientTeardown",
      )
      .apply { isDaemon = true }
      .start()

    ClientEventBus.off(this::onClientEvent)
  }

  /** Disconnect the client & resources */
  private fun disconnect() {
    log.info { "Disconnecting" }
    if (!connectionExecutor.isExecutorThread) {
      log.warn { "Disconnect called from non-executor thread, scheduling" }
      connectionExecutor.submit { disconnect() }
      return
    }

    socket = disposeOf(socket)
    messageHandler = disposeOf(messageHandler)
  }

  /** Wait for socket to close if connected */
  fun waitForSocket() {
    log.debug { "Waiting for socket to stop/close" }
    socket?.waitFor()
  }

  companion object {
    private val log = KLoggingManager.logger(Client::class.java.simpleName)
  }
}
