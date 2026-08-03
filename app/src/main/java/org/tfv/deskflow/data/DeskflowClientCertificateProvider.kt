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

package org.tfv.deskflow.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.tfv.deskflow.client.net.tls.ClientCertificateProvider
import org.tfv.deskflow.client.net.tls.DeskflowFingerprint

/**
 * Software-backed mTLS client certificate for Deskflow PeerAuth.
 *
 * The Deskflow server requires a client certificate. AndroidKeyStore keys can't
 * satisfy Conscrypt's raw-RSA signing upcall during the handshake, so this uses
 * a software RSA-2048 keypair with a self-signed cert (CN=Deskflow, SHA-256),
 * stored in a password-protected PKCS12 in app-private storage. The software key
 * signs through the JCA software provider, so Conscrypt's upcall succeeds.
 */
class DeskflowClientCertificateProvider(context: Context) : ClientCertificateProvider {

  private val file by lazy { File(context.filesDir, FILE_NAME) }

  @Synchronized
  fun ensureCertificateExists() {
    if (file.exists()) return
    val keyPairGen = KeyPairGenerator.getInstance("RSA")
    keyPairGen.initialize(2048)
    val keyPair = keyPairGen.generateKeyPair()
    val privateKey: PrivateKey = keyPair.private

    val now = System.currentTimeMillis()
    val name = X500Name("CN=Deskflow")
    val signerBuilder = JcaContentSignerBuilder("SHA256withRSA")
    val contentSigner: ContentSigner = signerBuilder.build(privateKey)
    val certBuilder =
      JcaX509v3CertificateBuilder(
        name,
        BigInteger.ONE,
        Date(now),
        Date(now + TEN_YEARS_MILLIS),
        name,
        keyPair.public,
      )
    val certHolder: X509CertificateHolder = certBuilder.build(contentSigner)
    val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(certHolder)

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    val chain: Array<Certificate> = arrayOf(cert)
    ks.setKeyEntry(ALIAS, privateKey, PASSWORD, chain)
    FileOutputStream(file).use { ks.store(it, PASSWORD) }
  }

  private fun loadKeyStore(ensure: Boolean): KeyStore {
    if (ensure) ensureCertificateExists()
    val ks = KeyStore.getInstance("PKCS12")
    FileInputStream(file).use { ks.load(it, PASSWORD) }
    return ks
  }

  fun clientCertificate(): X509Certificate? =
    try {
      loadKeyStore(ensure = true).getCertificate(ALIAS) as? X509Certificate
    } catch (e: Exception) {
      null
    }

  fun fingerprintColonHex(): String? =
    clientCertificate()?.let {
      DeskflowFingerprint.colonHexUpper(DeskflowFingerprint.sha256HexLower(it))
    }

  fun fingerprintIfPresent(): String? =
    try {
      if (!file.exists()) null
      else
        (loadKeyStore(ensure = false).getCertificate(ALIAS) as? X509Certificate)?.let {
          DeskflowFingerprint.colonHexUpper(DeskflowFingerprint.sha256HexLower(it))
        }
    } catch (e: Exception) {
      null
    }

  override fun keyManagers(): Array<KeyManager>? =
    try {
      val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      factory.init(loadKeyStore(ensure = true), PASSWORD)
      factory.keyManagers
    } catch (e: Exception) {
      null
    }

  companion object {
    private const val FILE_NAME = "deskflow-client-cert.p12"
    private const val ALIAS = "deskflow-client"
    private const val TEN_YEARS_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000
    private val PASSWORD = "deskflow-mtls".toCharArray()
  }
}
