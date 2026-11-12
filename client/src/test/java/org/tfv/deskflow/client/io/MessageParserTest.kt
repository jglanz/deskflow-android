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

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import org.tfv.deskflow.client.io.msgs.InvalidMessageException
import org.tfv.deskflow.client.io.msgs.KeepAliveMessage
import org.tfv.deskflow.client.io.msgs.NoOpMessage

/** Frames the [payload] as the wire would: 4-byte big-endian size prefix + payload bytes. */
private fun frame(payload: String): ByteArray {
  val bytes = payload.toByteArray(Charsets.UTF_8)
  val baos = ByteArrayOutputStream()
  DataOutputStream(baos).use {
    it.writeInt(bytes.size)
    it.write(bytes)
  }
  return baos.toByteArray()
}

private fun sizePrefix(size: Int): ByteArray =
  ByteArrayOutputStream().also { DataOutputStream(it).use { d -> d.writeInt(size) } }.toByteArray()

class MessageParserTest {

  @Test
  fun fullFrameParsesOneMessage() {
    val buf = DynamicByteBuffer().apply { append(frame("CNOP")) }
    val msgs = MessageParser().parseBuffer(buf)
    assertEquals(1, msgs.size)
    assertTrue(msgs[0] is NoOpMessage)
  }

  @Test
  fun prefixOnlyPreservesStateAcrossCalls() {
    // Same parser + buffer: read the size header, then in a second call append the body.
    val parser = MessageParser()
    val buf = DynamicByteBuffer().apply { append(sizePrefix(4)) }
    assertEquals(0, parser.parseBuffer(buf).size, "no body yet -> no messages")
    buf.append("CNOP".toByteArray(Charsets.UTF_8))
    val msgs = parser.parseBuffer(buf)
    assertEquals(1, msgs.size)
    assertTrue(msgs[0] is NoOpMessage)
  }

  @Test
  fun twoBackToBackFrames() {
    val buf = DynamicByteBuffer().apply {
      append(frame("CNOP"))
      append(frame("CALV"))
    }
    val msgs = MessageParser().parseBuffer(buf)
    assertEquals(2, msgs.size)
    assertTrue(msgs[0] is NoOpMessage)
    assertTrue(msgs[1] is KeepAliveMessage)
  }

  @Test
  fun nonPositiveSizeThrowsAndParserStaysUsable() {
    val parser = MessageParser()
    val buf = DynamicByteBuffer().apply { append(sizePrefix(0)) }
    assertThrows(InvalidMessageException::class.java) { parser.parseBuffer(buf) }
    // After the throw the parser state was reset; a following good frame parses cleanly.
    buf.append(frame("CNOP"))
    val msgs = parser.parseBuffer(buf)
    assertEquals(1, msgs.size)
    assertTrue(msgs[0] is NoOpMessage)
  }

  @Test
  fun oversizeSizeThrows() {
    val buf = DynamicByteBuffer().apply {
      append(sizePrefix(MessageParser.MAX_MESSAGE_SIZE + 1))
    }
    assertThrows(InvalidMessageException::class.java) {
      MessageParser().parseBuffer(buf)
    }
  }
}
