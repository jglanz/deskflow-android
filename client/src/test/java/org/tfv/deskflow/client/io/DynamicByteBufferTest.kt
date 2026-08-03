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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class DynamicByteBufferTest {

    /**
     * Appending more than the initial capacity must grow the backing array
     * (by doubling) while preserving every previously written byte.
     */
    @Test
    fun appendBeyondInitialCapacityGrowsBuffer() {
        val buf = DynamicByteBuffer(initialCapacity = 4)
        assertEquals(4, buf.capacity)
        assertEquals(0, buf.availableReadSize)

        val payload = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertEquals(payload.size, buf.append(payload))

        // Growth strategy doubles: 4 -> 8 -> 16, enough to hold 10 bytes.
        assertTrue(buf.capacity >= payload.size)
        assertEquals(16, buf.capacity)
        assertEquals(payload.size, buf.availableReadSize)

        assertArrayEquals(payload, buf.pop(payload.size))
        assertEquals(0, buf.availableReadSize)
    }

    /**
     * [DynamicByteBuffer.peek] must return the live bytes at the current read
     * position without consuming them, so it can be called repeatedly.
     */
    @Test
    fun peekDoesNotAdvanceReadPosition() {
        val buf = DynamicByteBuffer()
        buf.append(byteArrayOf(1, 2, 3, 4))
        assertEquals(4, buf.availableReadSize)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), buf.peek(4))
        assertEquals(4, buf.availableReadSize)

        // Idempotent: a second peek returns the same bytes, still no advance.
        assertArrayEquals(byteArrayOf(1, 2), buf.peek(2))
        assertEquals(4, buf.availableReadSize)
    }

    /**
     * The lambda form of [DynamicByteBuffer.peek] exposes the whole backing
     * slice without advancing the read position.
     */
    @Test
    fun peekVisitorReportsAvailableBytesWithoutConsuming() {
        val buf = DynamicByteBuffer()
        buf.append(byteArrayOf(1, 2, 3))

        val reported = buf.peek { _, _, offset, length ->
            assertEquals(0, offset)
            length
        }
        assertEquals(3, reported)
        assertEquals(3, buf.availableReadSize)
    }

    /**
     * [DynamicByteBuffer.read] advances the read position, so the readable
     * byte count drops by the number of bytes read.
     */
    @Test
    fun readAdvancesReadPosition() {
        val buf = DynamicByteBuffer()
        buf.append(byteArrayOf(1, 2, 3, 4))
        assertEquals(4, buf.availableReadSize)

        assertArrayEquals(byteArrayOf(1, 2), buf.read(2))
        assertEquals(2, buf.availableReadSize)
    }

    /**
     * [DynamicByteBuffer.pop] both consumes and compacts: it shifts the
     * remaining unread bytes forward so subsequent pops keep returning the
     * correct data in order. (A plain `read` would leave stale data and break
     * continued consumption — compaction is what makes sequential reads work.)
     */
    @Test
    fun popCompactsAndKeepsRemainingDataReadable() {
        val buf = DynamicByteBuffer()
        buf.append(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5, buf.availableReadSize)

        assertArrayEquals(byteArrayOf(1, 2), buf.pop(2))
        assertEquals(3, buf.availableReadSize)

        // After compaction the tail is still contiguous and in order.
        assertArrayEquals(byteArrayOf(3, 4, 5), buf.pop(3))
        assertEquals(0, buf.availableReadSize)
    }

    /**
     * Two messages appended back-to-back must be readable as two distinct,
     * ordered frames via repeated pop.
     */
    @Test
    fun sequentialPopOfTwoAppendedMessages() {
        val buf = DynamicByteBuffer()
        val first = byteArrayOf(10, 20, 30, 40)
        val second = byteArrayOf(50, 60, 70, 80)

        assertEquals(first.size, buf.append(first))
        assertEquals(second.size, buf.append(second))
        assertEquals(first.size + second.size, buf.availableReadSize)

        assertArrayEquals(first, buf.pop(first.size))
        assertArrayEquals(second, buf.pop(second.size))
        assertEquals(0, buf.availableReadSize)
    }

    /**
     * [DynamicByteBuffer.availableReadSize] must reflect every operation:
     * grows on append, unchanged by peek, shrinks on pop, cleared by reset.
     */
    @Test
    fun availableReadSizeStaysConsistentAcrossOperations() {
        val buf = DynamicByteBuffer()
        assertEquals(0, buf.availableReadSize)

        buf.append(byteArrayOf(1, 2, 3))
        assertEquals(3, buf.availableReadSize)

        buf.append(byteArrayOf(4, 5))
        assertEquals(5, buf.availableReadSize)

        // peek is side-effect free.
        buf.peek(2)
        assertEquals(5, buf.availableReadSize)

        assertArrayEquals(byteArrayOf(1, 2), buf.pop(2))
        assertEquals(3, buf.availableReadSize)

        buf.reset()
        assertEquals(0, buf.availableReadSize)
    }

    /**
     * [DynamicByteBuffer.reset] discards all buffered data and returns the
     * buffer to an empty, reusable state.
     */
    @Test
    fun resetDiscardsAllData() {
        val buf = DynamicByteBuffer()
        buf.append(byteArrayOf(1, 2, 3))
        assertEquals(3, buf.availableReadSize)

        buf.reset()
        assertEquals(0, buf.availableReadSize)

        // Buffer is reusable after reset.
        buf.append(byteArrayOf(7, 8))
        assertEquals(2, buf.availableReadSize)
        assertArrayEquals(byteArrayOf(7, 8), buf.pop(2))
    }

    /**
     * The [DynamicByteBuffer.dataInputStream] view consumes bytes via pop and
     * decodes them correctly — both raw bytes (readFully) and a big-endian
     * 4-byte integer (readInt).
     */
    @Test
    fun dataInputStreamReadsAppendedBytes() {
        // Big-endian int 0x12345678.
        val intBuf = DynamicByteBuffer()
        intBuf.append(byteArrayOf(0x12, 0x34, 0x56, 0x78))
        assertEquals(0x12345678, intBuf.dataInputStream.readInt())
        assertEquals(0, intBuf.availableReadSize)

        // Raw bytes via readFully.
        val rawBuf = DynamicByteBuffer()
        rawBuf.append(byteArrayOf(10, 20, 30))
        val out = ByteArray(3)
        rawBuf.dataInputStream.readFully(out)
        assertArrayEquals(byteArrayOf(10, 20, 30), out)
        assertEquals(0, rawBuf.availableReadSize)
    }

    /**
     * The ByteBuffer overload of [DynamicByteBuffer.append] must transfer the
     * given number of bytes from the source buffer's current position.
     */
    @Test
    fun appendByteBufferOverloadWritesBytes() {
        val buf = DynamicByteBuffer()
        val src = ByteBuffer.wrap(byteArrayOf(7, 8, 9))

        assertEquals(3, buf.append(src, 3))
        assertEquals(3, buf.availableReadSize)
        assertArrayEquals(byteArrayOf(7, 8, 9), buf.pop(3))
    }
}
