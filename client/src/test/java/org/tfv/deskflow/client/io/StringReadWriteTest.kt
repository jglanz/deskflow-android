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
package org.tfv.deskflow.client.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression guard for the length prefix on [writeString]/[readString]: it must be a UTF-8
 * BYTE count, not a UTF-16 code-unit (`String.length`) count. Non-ASCII strings previously
 * had the prefix understate the payload and desync the following frame.
 */
class StringReadWriteTest {

  private data class RoundTrip(val prefix: Int, val decoded: String)

  private fun roundTrip(s: String): RoundTrip {
    val baos = ByteArrayOutputStream()
    DataOutputStream(baos).use { it.writeString(s) }
    val bytes = baos.toByteArray()
    val prefix = DataInputStream(ByteArrayInputStream(bytes)).use { it.readInt() }
    val decoded = DataInputStream(ByteArrayInputStream(bytes)).use { it.readString() }
    return RoundTrip(prefix, decoded)
  }

  @Test
  fun ascii() {
    val r = roundTrip("hello")
    assertEquals(5, r.prefix, "ASCII length prefix must be the UTF-8 byte count")
    assertEquals("hello", r.decoded)
  }

  @Test
  fun nonAsciiLatin() {
    // "café" is 4 UTF-16 code units but 5 UTF-8 bytes (é is 2 bytes).
    val r = roundTrip("café")
    assertEquals(5, r.prefix, "non-ASCII prefix must be the UTF-8 byte count, not str.length")
    assertEquals("café", r.decoded)
  }

  @Test
  fun emoji() {
    // "😀" is 1 UTF-16 code unit (a surrogate pair is 2 chars actually) — assert byte count.
    val r = roundTrip("😀")
    assertEquals("😀".toByteArray(Charsets.UTF_8).size, r.prefix)
    assertEquals("😀", r.decoded)
  }

  @Test
  fun mixed() {
    // a(1) + 😀(4) + b(1) = 6 UTF-8 bytes
    val r = roundTrip("a😀b")
    assertEquals(6, r.prefix)
    assertEquals("a😀b", r.decoded)
  }

  @Test
  fun empty() {
    val r = roundTrip("")
    assertEquals(0, r.prefix)
    assertEquals("", r.decoded)
  }

  @Test
  fun twoStringsDoNotDesync() {
    val baos = ByteArrayOutputStream()
    DataOutputStream(baos).use {
      it.writeString("café")
      it.writeString("😀")
    }
    val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
    assertEquals("café", dis.readString())
    assertEquals("😀", dis.readString())
  }
}
