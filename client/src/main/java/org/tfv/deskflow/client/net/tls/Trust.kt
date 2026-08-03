/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the software is
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

package org.tfv.deskflow.client.net.tls

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Persistent store of pinned server fingerprints, keyed by host. Implementations
 * live in the Android `app/` module (e.g. a DataStore/file-backed store); the
 * pure-JVM `client/` module only depends on this interface.
 */
interface ServerTrustStore {
  /** The pinned SHA-256 (lowercase hex) for [host], or null if none pinned yet. */
  fun pinnedFingerprint(host: String): String?

  /** Pin [sha256HexLower] for [host] (trust-on-first-use). */
  fun pin(host: String, sha256HexLower: String)

  /** Remove the pin for [host]. */
  fun clear(host: String)
}

/**
 * Source of client certificate [KeyManager]s for mutual TLS (Deskflow `PeerAuth`
 * mode). Android `app/` provides an AndroidKeyStore-backed implementation; the
 * pure-JVM `client/` module only depends on this interface. Returns null when no
 * client cert is configured (the default `Encrypted` mode never needs one).
 */
interface ClientCertificateProvider {
  fun keyManagers(): Array<KeyManager>?
}

/**
 * Trust manager that authenticates the Deskflow server by SHA-256 fingerprint,
 * mirroring the desktop's TOFU model: on first connection it pins the presented
 * certificate's fingerprint; on later connections it requires the fingerprint to
 * match the pin, rejecting mismatches (possible MITM / cert rotation). Unlike
 * the old `AllCertsTrustManager`, an unknown certificate is never silently
 * accepted beyond the very first time, and a changed one is refused.
 *
 * Chain/path validation is intentionally not performed, matching the desktop
 * (which bypasses OpenSSL chain verification and relies solely on the
 * fingerprint). System-CA validation, when wanted, is provided by
 * [systemDefaultTrustManager] instead.
 */
class FingerprintTrustManager(
  private val host: String,
  private val store: ServerTrustStore,
) : X509TrustManager {

  /**
   * Candidate first-use fingerprint, staged during `checkServerTrusted` and only
   * persisted by [commitPin] once the TLS handshake actually completes. A
   * responder that fails the handshake (wrong server, transient, an attacker
   * that can't finish the handshake) is thus never pinned.
   */
  @Volatile private var pendingFingerprint: String? = null

  override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
    require(chain.isNotEmpty()) { "Empty server certificate chain" }
    val fingerprint = DeskflowFingerprint.sha256HexLower(chain[0])
    val pinned = store.pinnedFingerprint(host)
    when {
      pinned == null -> {
        // First use: stage the candidate; do NOT persist yet (see commitPin).
        pendingFingerprint = fingerprint
      }
      pinned.equals(fingerprint, ignoreCase = true) -> {
        // Trusted: fingerprint matches the pin.
      }
      else ->
        throw CertificateException(
          "Server certificate fingerprint for $host does not match the pinned " +
            "value (pinned=${pinned.take(16)}…, presented=${fingerprint.take(16)}…). " +
            "Possible MITM or server certificate change."
        )
    }
  }

  /**
   * Persist the staged first-use fingerprint. Called by the socket AFTER the TLS
   * handshake reaches FINISHED, so only a server that completed the full
   * handshake gets pinned. No-op if there was no pending candidate.
   */
  fun commitPin() {
    val fp = pendingFingerprint ?: return
    pendingFingerprint = null
    store.pin(host, fp)
  }

  override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
    // Client-certificate verification is the server's responsibility; this
    // manager runs on the client side and does not validate peer client certs.
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

/** The platform default trust manager (system CA store), used when no fingerprint store is configured. */
fun systemDefaultTrustManager(): X509TrustManager {
  val factory =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
  factory.init(null as KeyStore?)
  return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
}
