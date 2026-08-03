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
import org.tfv.deskflow.client.net.tls.ServerTrustStore

/**
 * Persistent SHA-256 fingerprint pinning store for Deskflow servers, keyed by
 * host. One `<host>=<sha256-lowerhex>` line per entry in app-private storage
 * (excluded from backup). Backs [org.tfv.deskflow.client.net.tls.FingerprintTrustManager]
 * for the Deskflow trust-on-first-use model.
 *
 * `filesDir` is resolved lazily so this is safe to construct from a Service field
 * initializer (before the service is fully attached).
 */
class TrustedServersFileStore(private val context: Context) : ServerTrustStore {

  private val file by lazy { File(context.filesDir, "trusted_servers.txt") }
  private val lock = Any()

  override fun pinnedFingerprint(host: String): String? =
    synchronized(lock) { readMap()[host] }

  override fun pin(host: String, sha256HexLower: String) =
    synchronized(lock) {
      val map = readMap()
      map[host] = sha256HexLower
      writeMap(map)
    }

  override fun clear(host: String) =
    synchronized(lock) {
      val map = readMap()
      if (map.remove(host) != null) writeMap(map)
    }

  private fun readMap(): MutableMap<String, String> {
    if (!file.exists()) return mutableMapOf()
    val map = mutableMapOf<String, String>()
    file.useLines { lines ->
      for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
      }
    }
    return map
  }

  private fun writeMap(map: Map<String, String>) {
    file.parentFile?.mkdirs()
    // Write to a sibling temp file then atomically replace, so a crash mid-write
    // can never leave the store empty/partial (which would silently wipe all
    // pins and reopen the first-use MITM window for previously-trusted hosts).
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.printWriter().use { w -> map.forEach { (h, fp) -> w.println("$h=$fp") } }
    try {
      java.nio.file.Files.move(
        tmp.toPath(),
        file.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
      java.nio.file.Files.move(
        tmp.toPath(),
        file.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }
}
