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

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * SHA-256 fingerprint helpers mirroring the Deskflow desktop client
 * (`SecureUtils.cpp`): the fingerprint is SHA-256 over the certificate's DER
 * encoding. Three renderings, matching the desktop so an Android user compares
 * the same hash the server GUI shows:
 *  - [sha256HexLower]   raw lowercase hex, no separators (used for storage/compare)
 *  - [colonHexUpper]    colon-separated UPPERCASE hex, wrapped at 24 chars/line (GUI)
 *  - [toDbLine]         `v2:sha256:<lowerhex>` (on-disk trusted-servers line)
 */
object DeskflowFingerprint {

  /** SHA-256 of the cert DER as 64-char lowercase hex (no separators). */
  fun sha256HexLower(cert: X509Certificate): String {
    val digest =
      MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
      val v = b.toInt() and 0xFF
      sb.append(HEX_LOWER[v ushr 4])
      sb.append(HEX_LOWER[v and 0x0F])
    }
    return sb.toString()
  }

  /** Colon-separated UPPERCASE hex (e.g. `AB:CD:..`), wrapped at ~24 chars/line. */
  fun colonHexUpper(sha256HexLower: String): String {
    val oneLine =
      sha256HexLower.uppercase().chunked(2).joinToString(":")
    return oneLine.chunked(24).joinToString("\n") { line ->
      line.trimEnd(':')
    }
  }

  /** On-disk trusted-servers line format: `v2:sha256:<lowerhex>`. */
  fun toDbLine(sha256HexLower: String): String = "v2:sha256:$sha256HexLower"

  private val HEX_LOWER = "0123456789abcdef".toCharArray()
}
