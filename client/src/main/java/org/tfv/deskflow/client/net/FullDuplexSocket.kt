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


import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tfv.deskflow.client.io.DynamicByteBuffer
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.ISimpleEventEmitter
import org.tfv.deskflow.client.util.SimpleEventEmitter
import org.tfv.deskflow.client.util.logging.KLoggingManager

class FullDuplexSocket(
  private val host: String,
  private val port: Int,
  private val useTls: Boolean,
  private val fingerprintVerificationCallback: FingerprintVerificationCallback? = null,
  private val clientKeyManager: KeyManager? = null,
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
  private val trustManager = AllCertsTrustManager()

  // Fingerprint verification state
  @Volatile private var fingerprintVerified = false

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
    val threadToJoin: Thread?

    synchronized(threadLock) {
      if (isStopped.exchange(true)) {
        log.warn { "Socket is already stopped" }
        return@stop
      }

      threadToJoin = this.thread
      this.thread = null

      // Close selector first to wake up any blocking select() call
      val selector = this.selector
      if (selector != null) {
        try {
          selector.close()
        } catch (_: Exception) {}
        this.selector = null
      }

      // Close channel to ensure socket thread can't do more I/O
      val channel = this.channel
      if (channel != null) {
        try {
          channel.close()
        } catch (_: Exception) {}
        this.channel = null
      }
    }

    if (threadToJoin != null) {
      log.trace { "Interrupting socket thread" }
      try {
        threadToJoin.interrupt()
      } catch (_: Exception) {}

      log.trace { "Joining socket thread" }
      try {
        threadToJoin.join(1000)
        if (threadToJoin.isAlive) {
          log.warn { "Socket thread did not terminate within 1 second" }
        }
      } catch (_: Exception) {}
    }

    log.trace { "Stopped socket" }
    if (!skipEmit) {
      emit(SocketEvent.DisconnectEvent(this))
    }
    this.clear()
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
    if (isStopped.load()) {
      log.warn { "Socket is stopped, ignoring send" }
      return
    }

    // Enqueue the next message
    outbound.put(ByteBuffer.wrap(data))

    // Wake up selector in case it's blocked
    try {
      selector?.wakeup()
    } catch (_: Exception) {}

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
          val res = sslEngine.unwrap(netInBuffer, appInBuffer)
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
  }

  private suspend fun suspendForFingerprintVerification(certificateFingerprint: CertificateFingerprint): Boolean {
    return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
      fingerprintVerificationCallback?.let { callback ->
        try {
          runBlocking {
            callback.onFingerprintChallenge(certificateFingerprint) { accepted ->
              continuation.resume(accepted)
            }
          }
        } catch (e: Exception) {
          log.error(e) { "Error in fingerprint callback" }
          continuation.resume(false)
        }
      } ?: run {
        continuation.resume(false)
      }
    }
  }

  private fun runLoop() {

    log.trace { "Socket thread started" }

    try {
      selector = Selector.open()
      val readBuffer = DynamicByteBuffer()
      val sel = selector!!
      val connectStartTime = System.currentTimeMillis()
      val connectTimeoutMs = 9000L

      channel =
        when {
          useTls -> {
            // Initialize SSL context and engine for client mode
            sslContext = SSLContext.getInstance("TLS")
            val sslContext = sslContext!!
            // Use client key manager if provided for client certificate authentication
            val keyManagers = if (clientKeyManager != null) arrayOf(clientKeyManager) else null
            sslContext.init(keyManagers, arrayOf(trustManager), null)
            sslEngine = sslContext.createSSLEngine(host, port)
            val sslEngine = sslEngine!!
            sslEngine.useClientMode = true

            sslEngine.beginHandshake()
            val session: SSLSession = sslEngine.session

            // Allocate buffers based on session sizes
            netOutBuffer = ByteBuffer.allocate(session.packetBufferSize)
            netInBuffer = ByteBuffer.allocate(session.packetBufferSize)
            appInBuffer = ByteBuffer.allocate(session.applicationBufferSize)

            // Open non-blocking channel for TLS handshake
            SocketChannel.open().apply {
              configureBlocking(false)
              // Disable Nagle: the protocol is a stream of tiny messages (mouse
              // moves are a few bytes) that Nagle would otherwise batch.
              setOption(StandardSocketOptions.TCP_NODELAY, true)
              connect(InetSocketAddress(host, port))
              register(sel, SelectionKey.OP_CONNECT)
            }
          }

          else ->
            SocketChannel.open().apply {
              configureBlocking(false)
              // Disable Nagle: the protocol is a stream of tiny messages (mouse
              // moves are a few bytes) that Nagle would otherwise batch.
              setOption(StandardSocketOptions.TCP_NODELAY, true)
              connect(InetSocketAddress(host, port))
              register(sel, SelectionKey.OP_CONNECT)
            }
        }
      var tcpConnected = false
      while (!Thread.currentThread().isInterrupted) {
        // Block with timeout to allow periodic wake-ups for dead connection detection
        sel.select(1000L) // 1 second timeout

        // Check for connect timeout
        if (!tcpConnected) {
          val elapsed = System.currentTimeMillis() - connectStartTime
          if (elapsed > connectTimeoutMs) {
            log.warn { "Connection timeout after ${elapsed}ms" }
            throw java.io.IOException("Connection timeout after ${elapsed}ms")
          }
        }

        // Get selected keys
        val iter = sel.selectedKeys().iterator()

        // Process selected keys
        while (iter.hasNext()) {
          val key = iter.next().also { iter.remove() }

          when {
            key.isConnectable -> {
              val sc = key.channel() as SocketChannel
              if (sc.finishConnect()) {
                tcpConnected = true
                if (useTls) {
                  doHandshake(sc, sel)

                  // Verify server certificate fingerprint if TLS is enabled
                  if (fingerprintVerificationCallback != null) {
                    try {
                      // Extract certificate and compute fingerprint
                      val sslEngine = sslEngine ?: throw SSLException("SSLEngine is null")
                      val sslSession = sslEngine.session
                      val peerCertificates = sslSession.peerCertificates

                      if (peerCertificates.isEmpty()) {
                        throw SSLException("No peer certificates")
                      }

                      val serverCert = peerCertificates[0] as? X509Certificate
                        ?: throw SSLException("Cannot cast peer certificate to X509Certificate")

                      val fingerprint = FingerprintManager.computeFingerprint(serverCert)
                      log.info { "Server fingerprint for $host:$port: $fingerprint" }

                      // Check if client certificate was actually used in this connection
                      val localCerts = sslSession.localCertificates
                      val clientAuthRequested = localCerts != null && localCerts.isNotEmpty()
                      log.info { "Client certificate used in connection: $clientAuthRequested" }

                      // Pass certificate data to app layer for verification
                      val certData = CertificateFingerprint(serverCert, fingerprint, clientAuthRequested)

                      // Record timestamp before waiting for user decision
                      val verificationStartTime = System.currentTimeMillis()

                      // Wait for app layer decision
                      val accepted = runBlocking {
                        suspendForFingerprintVerification(certData)
                      }

                      val verificationDuration = System.currentTimeMillis() - verificationStartTime

                      if (accepted) {
                        log.info { "Fingerprint accepted (took ${verificationDuration}ms)" }
                        fingerprintVerified = true

                        // Deskflow protocol allows 30 seconds for handshake to complete
                        // So we reconnect if the (first-time manual) fingerprint check takes too long
                        if (verificationDuration > 25_000) {
                          log.info { "Fingerprint verification took too long (>${verificationDuration}ms), reconnecting" }
                          sc.close()
                          stop()
                          return@runLoop
                        }
                      } else {
                        log.warn { "Fingerprint rejected" }
                        sc.close()
                        stop()
                        return@runLoop
                      }
                    } catch (e: Exception) {
                      log.error(e) { "Exception during fingerprint verification" }
                      sc.close()
                      stop()
                      return@runLoop
                    }
                  } else {
                    // No callback provided, proceed without fingerprint verification
                    log.info { "No fingerprint verification callback provided, proceeding" }
                    fingerprintVerified = true
                  }
                } else {
                  fingerprintVerified = true
                }

                // Now interested in both read and write (only if fingerprint is verified or not required)
                if (fingerprintVerified) {
                  sc.register(sel, SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                  emit(SocketEvent.ConnectEvent(this))
                }
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

                    when (res.status) {
                      SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        // Break out and wait for more data
                        break
                      }
                      SSLEngineResult.Status.CLOSED -> {
                        log.info { "SSL engine closed" }
                        sc.close()
                        stop()
                        return@runLoop
                      }
                      else -> {}
                    }

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

  private class AllCertsTrustManager : X509TrustManager {
    @Suppress("TrustAllX509TrustManager")
    override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
    ) {
      // Verify server certificate complies with Deskflow protocol requirements
      if (chain.isEmpty()) {
        throw java.security.cert.CertificateException("Server certificate chain is empty")
      }

      val serverCert = chain[0]
      val publicKey = serverCert.publicKey

      // Verify key algorithm is RSA (DSA support removed from Deskflow server)
      if (publicKey.algorithm != "RSA") {
        throw java.security.cert.CertificateException(
          "Server certificate must use RSA key algorithm, but found: ${publicKey.algorithm}"
        )
      }

      // Verify RSA key size is at least 2048 bits
      if (publicKey is java.security.interfaces.RSAPublicKey) {
        val keySize = publicKey.modulus.bitLength()
        if (keySize < 2048) {
          throw java.security.cert.CertificateException(
            "Server RSA key size must be at least 2048 bits, but found: $keySize bits"
          )
        }
      } else {
        throw java.security.cert.CertificateException(
          "Server public key is not an RSA key"
        )
      }
    }

    @Suppress("TrustAllX509TrustManager")
    override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
    ) {

    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf() //
  }

}
