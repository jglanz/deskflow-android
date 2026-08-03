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
import java.io.IOException

@Throws(IOException::class)
fun DataInputStream.readString(): String {
    // Faithful inverse of DataOutputStreamExt.writeString():
    // 4-byte big-endian length prefix followed by exactly that many UTF-8 bytes.
    val length = readInt()
    val stringBytes = ByteArray(length)
    readFully(stringBytes)
    return String(stringBytes, Charsets.UTF_8)
}

@Throws(IOException::class)
fun DataInputStream.readFixedString(length: Int): String {
    // Read a fixed number of bytes (no length prefix) and decode as UTF-8.
    val stringBytes = ByteArray(length)
    readFully(stringBytes)
    return String(stringBytes, Charsets.UTF_8)
}

@Throws(IOException::class)
fun DataInputStream.readExpectedString(expectedString: String): String {
    val str = readFixedString(expectedString.length)

    require(str == expectedString) {
        "Expected string $expectedString not found.  Found: $str"
    }

    return str
}


