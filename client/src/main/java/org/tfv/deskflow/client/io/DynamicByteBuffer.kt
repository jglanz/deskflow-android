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

import java.io.DataInputStream
import java.nio.ByteBuffer

/**
 * An expandable byte buffer that automatically grows as needed,
 * supporting append, pop (with offset), and reset operations.
 *
 * @param initialCapacity starting capacity (minimum of 1)
 */
class DynamicByteBuffer(
    initialCapacity: Int = 1024 * 4
) {
    private val mutationLock = Any()
    private var buf: ByteArray
    private var readPos = 0    // absolute index of next byte to read/pop
    private var writePos = 0   // absolute index one past last written byte

    init {
        val cap = initialCapacity.coerceAtLeast(1)
        buf = ByteArray(cap)
    }

    /** Number of bytes currently buffered and unread */
    fun size(): Int = writePos

    val availableWriteSize: Int get() = capacity - writePos
    val availableReadSize: Int get() = (writePos - readPos).coerceAtLeast(0)

    /** Current underlying buffer capacity */
    val capacity: Int get() = buf.size

    val dataInputStream = DataInputStream(DynamicByteBufferInputStream(this))

    /** Ensure buffer can hold at least minCapacity live bytes */
    private fun ensureCapacity(minCapacity: Int) {
        synchronized(mutationLock) {
            if (minCapacity <= buf.size) return
            var newCap = buf.size
            while (newCap < minCapacity) newCap *= 2

            val newBuf = ByteArray(newCap)
            val oldCap = buf.size

            System.arraycopy(buf, 0, newBuf, 0, oldCap)

            buf = newBuf
        }
    }

    /**
     * Appends all bytes in [data] (expands if needed), returns number written.
     */
    fun append(data: ByteArray): Int {
        synchronized(mutationLock) {
            val toWrite = data.size
            ensureCapacity(size() + toWrite)


            System.arraycopy(data, 0, buf, writePos, toWrite)

            writePos += toWrite
            return toWrite
        }
    }

    fun append(data: ByteBuffer, length: Int): Int {
        synchronized(mutationLock) {
            val toWrite = length
            ensureCapacity(size() + toWrite)

            data.get(buf, writePos, toWrite)
//            System.arraycopy(, 0, buf, writePos, toWrite)

            writePos += toWrite
            return toWrite
        }
    }

    fun <T> peek(visitor: (buffer: DynamicByteBuffer, data: ByteArray, offset: Int, length: Int ) -> T): T {
        synchronized(mutationLock) {
            require(readPos <= writePos) { "No data to peek" }

            return visitor(this, buf, readPos, writePos - readPos)
        }

    }

    fun peek(length: Int, offset: Int = readPos): ByteArray {
        return read(length, offset, peek = true)
    }

    /**
     * Pops [length] bytes from the buffer at [offset] relative to current read position,
     * then advances the read position past the popped bytes (including offset).
     *
     * @param length number of bytes to pop
     * @param offset number of bytes to skip before popping
     * @return a ByteArray of length [length]
     * @throws IllegalArgumentException if offset or length is negative or exceeds available data
     */
    @Suppress("DuplicatedCode")
    fun read(length: Int, offset: Int = readPos, peek: Boolean = false): ByteArray {
        synchronized(mutationLock) {
            require(length >= 0 && offset >= 0) { "length and offset must be >= 0" }

            val available = availableReadSize
            require(offset + length <= available) { "Not enough data to pop" }

            val start = offset
            val toRead = length.coerceAtMost(available - start)
            val dest = ByteArray(toRead)
            require(toRead == read(dest, toRead, start, peek)) { "read() did not return the expected number of bytes" }

//            System.arraycopy(buf, start, result, 0, toRead)
//
//            // advance readPos past skipped + popped bytes
//            if (!peek)
//                readPos = (offset + length)

            return dest
        }
    }

    fun read(dest: ByteArray, length: Int, offset: Int = readPos, peek: Boolean = false): Int {
        synchronized(mutationLock) {
            require(length >= 0 && offset >= 0) { "length and offset must be >= 0" }

            val available = availableReadSize
            require(offset + length <= available) { "Not enough data to pop" }

            val start = offset
            val toRead = length.coerceAtMost(available - start)
            require(toRead <= dest.size) { "Destination byte array is too small" }
//            val result = ByteArray(toRead)


            System.arraycopy(buf, start, dest, 0, toRead)

            // advance readPos past skipped + popped bytes
            if (!peek)
                readPos = (offset + length)

            return toRead
        }
    }
    fun pop(dest: ByteArray, length: Int, offset: Int = readPos): Int {
        synchronized(mutationLock) {
            val count = read(dest, length, offset)
            if (count > 0) {
                System.arraycopy(buf, readPos, buf, offset, capacity - readPos)
                readPos -= count
                writePos -= count
            }
            return count
        }
    }
    fun pop(length: Int, offset: Int = readPos): ByteArray {
        synchronized(mutationLock) {
            val data = read(length, offset)
            if (data.isNotEmpty()) {
                System.arraycopy(buf, readPos, buf, offset, capacity - readPos)
                readPos -= data.size
                writePos -= data.size
            }
            return data
        }
    }

    /**
     * Resets the buffer, discarding all data and resetting positions to zero.
     */
    fun reset() {
        synchronized(mutationLock) {
            readPos = 0
            writePos = 0
        }
    }


    private class DynamicByteBufferInputStream(
        private val buffer: DynamicByteBuffer
    ) : java.io.InputStream() {
        override fun available(): Int {
            return buffer.availableReadSize
        }

        override fun read(): Int {
            return synchronized(buffer.mutationLock) {
                if (buffer.availableReadSize > 0) {
                    val data = buffer.pop(1)
                    data[0].toInt()
                } else -1
            }
        }


        override fun read(dest: ByteArray, offset: Int, len: Int): Int {
            return synchronized(buffer.mutationLock) {
                when {
                    len <= 0 -> 0
                    buffer.availableReadSize <= 0 -> -1
                    else -> {
                        // Read up to len bytes (short read when less is available),
                        // placing them at the destination offset. Source is always
                        // the current read position; offset is dest placement only.
                        val toRead = minOf(len, buffer.availableReadSize)
                        val data = buffer.pop(toRead)
                        System.arraycopy(data, 0, dest, offset, toRead)
                        toRead
                    }
                }
            }
        }
    }

}
