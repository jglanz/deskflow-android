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

@file:OptIn(ExperimentalAtomicApi::class)

package org.tfv.deskflow.client.net


import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.tfv.deskflow.client.io.DynamicByteBuffer
import org.tfv.deskflow.client.net.tls.ClientCertificateProvider
import org.tfv.deskflow.client.net.tls.FingerprintTrustManager
import org.tfv.deskflow.client.net.tls.ServerTrustStore
import org.tfv.deskflow.client.net.tls.systemDefaultTrustManager
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.ISimpleEventEmitter
import org.tfv.deskflow.client.util.SimpleEventEmitter
import org.tfv.deskflow.client.util.logging.KLoggingManager

class FullDuplexSocket(
  private val host: String,
  private val port: Int,
  private val useTls: Boolean,
  /**
   * Optional persistent fingerprint store. When provided, TLS authenticates the
   * server by SHA-256 fingerprint TOFU (Deskflow parity). When null, TLS uses the
   * platform/system CA trust manager (secure default; rejects self-signed certs).
   */
  private val trustStore: ServerTrustStore? = null,
  /** Optional client-certificate provider for mutual TLS (Deskflow `PeerAuth`). */
  private val clientCertProvider: ClientCertificateProvider? = null,
) :
  AbstractDisposable(),
  ISimpleEventEmitter<FullDuplexSocket.SocketEvent> by SimpleEventEmitter<
    SocketEvent
  >() {

  /** The socket channel used for communication */
  @Volatile private var channel: SocketChannel? = null

  /** Queue for outbound messages */
  private val outbound = LinkedBlockingQueue<ByteBuffer>()

  /** The thread that runs the socket connection */
  @Volatile private var thread: Thread? = null

  /** Lock to synchronize access to the socket thread */
  private val threadLock = Any()

  /** The selector used to manage the socket channel */
  @Volatile private var selector: Selector? = null

  // TLS support
  private var sslContext: SSLContext? = null
  private var sslEngine: SSLEngine? = null
  private var netOutBuffer: ByteBuffer? = null
  private var netInBuffer: ByteBuffer? = null
  private var appInBuffer: ByteBuffer? = null
  private var tlsTrustManager: X509TrustManager? = null

  /** Check if the socket thread is running */
  val isRunning: Boolean
    get() = thread?.isAlive ?: false

  /** Check if the socket is connected. */
  val isConnected: Boolean
    get() = isRunning && channel?.isConnected ?: false

  /** Start the socket connection. */
  fun start() {
    log.trace { "Connecting to $host:$port" }
    synchronized(threadLock) {
      if (thread != null || isRunning) {
        return@start
      }
    }
    thread = Thread({ runLoop() }, FullDuplexSocket::class.java.simpleName)
    thread!!.start()
  }

  private val isStopped = AtomicBoolean(false)

  fun stop(skipEmit: Boolean = false) {
    synchronized(threadLock) {
      if (isStopped.exchange(true)) {
        log.warn { "Socket is already stopped" }
        return@stop
      }

      val thread = this.thread

      log.trace { "Interrupting socket" }
      try {
        thread?.interrupt()
      } catch (err: Exception) {}

      log.trace { "Joining socket" }
      try {
        thread?.join()
      } catch (err: Exception) {}
      this.thread = null

      val channel = channel
      if (channel != null) {
        if (channel.isConnected) {
          try {
            channel.close()
          } catch (err: Exception) {}
        }
        this.channel = null
      }

      val selector = selector
      if (selector != null) {
        try {
          selector.close()
        } catch (err: Exception) {}
        this.selector = null
      }

      log.trace { "Stopped socket" }
      if (!skipEmit) {
        emit(SocketEvent.DisconnectEvent(this))
      }
      this.clear()
    }
  }

  override fun onDispose() {
    clear()
    stop(true)
  }

  fun waitFor() {
    val thread = this.thread
    if (thread == null || !thread.isAlive) {
      return
    }
    thread.join()
  }

  fun send(data: ByteArray) {
    synchronized(threadLock) {
      if (!isRunning) {
        log.warn { "Socket is not running" }
        return
      }
      // Enqueue, then wake the selector. Only the selector thread mutates
      // SelectionKey.interestOps (it re-arms OP_WRITE at the top of the loop and
      // clears it once the queue drains); mutating it here from the caller
      // thread raced with that clear and could strand a queued message.
      outbound.put(ByteBuffer.wrap(data))
    }
    selector?.wakeup()

    log.trace { "Send message queued ${data.size}" }
  }

  @Throws(SSLException::class)
  private fun doHandshake(sc: SocketChannel, sel: Selector) {
    val sslEngine = sslEngine ?: throw SSLException("SSLEngine is null")
    val netInBuffer = netInBuffer ?: throw SSLException("netInBuffer is null")
    val netOutBuffer =
      netOutBuffer ?: throw SSLException("netOutBuffer is null")
    var hsStatus = sslEngine.handshakeStatus
    while (
      hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
        hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
    ) {
      when (hsStatus) {
        SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
          if (sc.read(netInBuffer) < 0)
            throw SSLException("Channel closed during handshake")
          netInBuffer.flip()
          var res = sslEngine.unwrap(netInBuffer, appInBuffer)
          // Grow the app buffer on OVERFLOW so the handshake can't stall on a
          // too-small destination. (UNDERFLOW here is fine on the non-blocking
          // path — the outer while simply re-enters NEED_UNWRAP for more bytes.)
          if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            appInBuffer = ByteBuffer.allocate(sslEngine.session.applicationBufferSize)
            res = sslEngine.unwrap(netInBuffer, appInBuffer)
          }
          netInBuffer.compact()
          hsStatus = res.handshakeStatus
        }
        SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
          netOutBuffer.clear()
          val res = sslEngine.wrap(ByteBuffer.allocate(0), netOutBuffer)
          netOutBuffer.flip()
          while (netOutBuffer.hasRemaining()) sc.write(netOutBuffer)
          hsStatus = res.handshakeStatus
        }
        SSLEngineResult.HandshakeStatus.NEED_TASK -> {
          var task: Runnable? = sslEngine.delegatedTask
          while (task != null) {
            task.run()
            task = sslEngine.delegatedTask
          }
          hsStatus = sslEngine.handshakeStatus
        }
        else -> throw SSLException("Unexpected handshake status: $hsStatus")
      }
    }
    // The handshake reached FINISHED/NOT_HANDSHAKING — only now persist the
    // staged first-use fingerprint, so a responder that failed the handshake is
    // never pinned.
    (tlsTrustManager as? FingerprintTrustManager)?.commitPin()
  }

  private fun runLoop() {

    log.trace { "Socket thread started" }

    try {
      selector = Selector.open()
      val readBuffer = DynamicByteBuffer()
      val sel = selector!!

      channel =
        when {
          useTls -> {
            // Initialize SSL context and engine for client mode.
            // Authenticate the server by SHA-256 fingerprint TOFU when a trust
            // store is configured (Deskflow parity); otherwise fall back to the
            // platform/system CA trust manager. A client certificate is presented
            // only when a provider is configured (Deskflow PeerAuth).
            sslContext = SSLContext.getInstance("TLS")
            val sslContext = sslContext!!
            val trustManager =
              if (trustStore != null) {
                FingerprintTrustManager(host, trustStore)
              } else {
                systemDefaultTrustManager()
              }
            tlsTrustManager = trustManager
            sslContext.init(clientCertProvider?.keyManagers(), arrayOf(trustManager), null)
            sslEngine = sslContext.createSSLEngine(host, port)
            val sslEngine = sslEngine!!
            sslEngine.useClientMode = true
            // System-CA fallback only: also enforce hostname verification. The
            // fingerprint TOFU path is bound by the host-keyed pin and Deskflow
            // servers use self-signed certs without host-matching SANs, so HTTPS
            // verification must NOT be enabled there (it would reject them).
            if (trustStore == null) {
              val params = sslEngine.sslParameters
              params.endpointIdentificationAlgorithm = "HTTPS"
              sslEngine.sslParameters = params
            }

            sslEngine.beginHandshake()
            val session: SSLSession = sslEngine.session

            // Allocate buffers based on session sizes
            netOutBuffer = ByteBuffer.allocate(session.packetBufferSize)
            netInBuffer = ByteBuffer.allocate(session.packetBufferSize)
            appInBuffer = ByteBuffer.allocate(session.applicationBufferSize)

            // Open non-blocking channel for TLS handshake
            SocketChannel.open().apply {
              configureBlocking(false)
              connect(InetSocketAddress(host, port))
              register(sel, SelectionKey.OP_CONNECT)
            }
          }

          else ->
            SocketChannel.open().apply {
              configureBlocking(false)
              connect(InetSocketAddress(host, port))
              register(sel, SelectionKey.OP_CONNECT)
            }
        }
      while (!Thread.currentThread().isInterrupted) {
        // Re-arm OP_WRITE if there is outbound data. Only this (selector) thread
        // touches interestOps, so there's no race with send() (which only
        // enqueues + wakes). Once the writable branch drains the queue it clears
        // OP_WRITE again, so select() can block while idle.
        val writeKey = channel?.keyFor(sel)
        if (writeKey != null && writeKey.isValid && outbound.isNotEmpty()) {
          writeKey.interestOps(writeKey.interestOps() or SelectionKey.OP_WRITE)
        }

        // blocks until an event or wakeup()
        sel.select()

        // Get selected keys
        val iter = sel.selectedKeys().iterator()

        // Process selected keys
        while (iter.hasNext()) {
          val key = iter.next().also { iter.remove() }

          when {
            key.isConnectable -> {
              val sc = key.channel() as SocketChannel
              if (sc.finishConnect()) {
                if (useTls) {
                  // Non-blocking handshake (same as the prior working build).
                  // doHandshake drives the SSLEngine over the non-blocking
                  // channel; brief UNDERFLOW re-loops are harmless on a LAN.
                  doHandshake(sc, sel)
                }
                // Read-only interest; OP_WRITE is armed on demand by the loop
                // when outbound is non-empty.
                sc.register(sel, SelectionKey.OP_READ)
                emit(SocketEvent.ConnectEvent(this))
              }
            }

            key.isReadable -> {
              val sc = key.channel() as SocketChannel
              if (useTls) {
                val bytesRead = sc.read(netInBuffer)
                if (bytesRead > 0) {
                  val netInBuffer =
                    netInBuffer ?: throw SSLException("netInBuffer is null")
                  val appInBuffer =
                    appInBuffer ?: throw SSLException("appInBuffer is null")
                  val sslEngine =
                    sslEngine ?: throw SSLException("sslEngine is null")
                  netInBuffer.flip()
                  while (netInBuffer.hasRemaining()) {
                    val res = sslEngine.unwrap(netInBuffer, appInBuffer)
                    if (res.bytesProduced() > 0) {
                      appInBuffer.flip()
                      readBuffer.append(appInBuffer, appInBuffer.remaining())
                      appInBuffer.compact()
                    }
                    if (
                      res.handshakeStatus !=
                        SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                    ) {
                      doHandshake(sc, sel)
                    }
                  }
                  netInBuffer.compact()
                  emit(SocketEvent.ReceiveEvent(readBuffer, this))
                } else if (bytesRead < 0) {
                  sc.close()
                  stop()
                }
              } else {
                // existing plaintext read logic
                val buf = ByteBuffer.allocate(4096)
                val bytes = sc.read(buf)
                if (bytes > 0) {
                  buf.flip()
                  readBuffer.append(buf, bytes)
                  emit(SocketEvent.ReceiveEvent(readBuffer, this))
                } else if (bytes < 0) {
                  sc.close()
                  stop()
                }
              }
            }

            key.isWritable -> {
              val sc = key.channel() as SocketChannel
              if (useTls) {
                val msg = outbound.poll()
                if (msg != null) {
                  val netOutBuffer =
                    netOutBuffer ?: throw SSLException("netOutBuffer is null")
                  val sslEngine =
                    sslEngine ?: throw SSLException("sslEngine is null")
                  netOutBuffer.clear()
                  val res = sslEngine.wrap(msg, netOutBuffer)
                  netOutBuffer.flip()
                  while (netOutBuffer.hasRemaining()) sc.write(netOutBuffer)
                  if (msg.hasRemaining()) outbound.put(msg)
                  if (
                    res.handshakeStatus !=
                      SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                  ) {
                    doHandshake(sc, sel)
                  }
                }
              } else { // Drain one message at a time
                val msg = outbound.poll()

                if (msg != null) {
                  log.trace { "Writing ${msg.remaining()} bytes" }
                  sc.write(msg) // If not fully written, put remainder back
                  if (msg.hasRemaining()) {
                    outbound.put(msg)
                  }
                }
              }
              // H4: outbound queue drained — drop OP_WRITE so select() can block.
              if (outbound.isEmpty()) key.interestOps(SelectionKey.OP_READ)
            }
          }
        }
      }
    } catch (err: Exception) {
      log.warn(err) { "Error in socket thread" }
      emit(SocketEvent.ErrorEvent(err, this))
      try {
        dispose()
      } catch (_: Exception) {}
    }
  }

  sealed class SocketEvent() {
    data class ConnectEvent(val socket: FullDuplexSocket) : SocketEvent()

    data class DisconnectEvent(val socket: FullDuplexSocket) : SocketEvent()

    data class ReceiveEvent(
      val buf: DynamicByteBuffer,
      val socket: FullDuplexSocket,
    ) : SocketEvent()

    data class ErrorEvent(val err: Exception, val socket: FullDuplexSocket) :
      SocketEvent()
  }

  companion object {

    private val log =
      KLoggingManager.logger(FullDuplexSocket::class.java.simpleName)


  }

}
