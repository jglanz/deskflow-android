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

import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.client.io.MessageTemplate.Companion.templateFromPrefix
import org.tfv.deskflow.client.io.msgs.InvalidMessageException
import org.tfv.deskflow.client.io.msgs.Message
import kotlin.reflect.full.createInstance

import java.io.ByteArrayInputStream
import java.io.DataInputStream

class MessageParser() {

    private var pendingMessageSize: Int = 0

    /**
     * Parse the message from the buffer
     *
     * @param buffer The buffer to parse
     * @return The number of messages parsed
     */
    fun parseBuffer(buffer: DynamicByteBuffer): List<Message> {
        log.debug { "parse buffer size: ${buffer.size()}" }
        val inputStream = buffer.dataInputStream
        val msgList = mutableListOf<Message>()
        while (true) {
            var availableSize = buffer.availableReadSize
            if (pendingMessageSize == 0) {
                if (availableSize < Int.SIZE_BYTES) {
                    break
                }

                pendingMessageSize = inputStream.readInt()
                availableSize -= Int.SIZE_BYTES

                // readInt() is signed big-endian, so a malicious/buggy server
                // controls both the sign and magnitude of the frame size. Reject
                // non-positive or absurdly large values before they drive
                // pop()/accumulation.
                if (pendingMessageSize <= 0 || pendingMessageSize > MAX_MESSAGE_SIZE) {
                    val badSize = pendingMessageSize
                    log.error {
                        "Invalid message size $badSize (cap=$MAX_MESSAGE_SIZE); " +
                            "resetting parser and closing connection"
                    }
                    pendingMessageSize = 0
                    buffer.reset()
                    throw InvalidMessageException("Invalid message size: $badSize")
                }
            }

            if (availableSize < pendingMessageSize) {
                break
            }

            try {
                val messageData = buffer.pop(pendingMessageSize)
                pendingMessageSize = 0

                val message = parseMessage(messageData)
                if (message == null) {
                    log.warn { "Error parsing message of size ${messageData.size}" }
                    continue
                }
                msgList.add(message)
            } catch (err: Throwable) {
                // One malformed frame (or an OutOfMemoryError from a bogus string
                // length) must not crash the receive thread. Reset parser state
                // and rethrow as a connection-level error so the socket run loop
                // closes the connection cleanly instead of letting the throw
                // escape and silently wedge the receiver.
                log.error(err) {
                    "Failed to parse frame of size $pendingMessageSize; " +
                        "resetting parser and closing connection"
                }
                pendingMessageSize = 0
                buffer.reset()
                throw InvalidMessageException("Malformed message frame: ${err.message}")
            }

        }

        return msgList
    }

    fun parseMessage(data: ByteArray): Message? {
        try {
            require(data.size >= 4) { "Message data must be at least 4 bytes" }
            val prefix = String(data, 0, 4)
            val template = templateFromPrefix(prefix)
            if (template == null) {
                log.error { "Template not found for prefix: $prefix" }
                return null
            }
            log.debug { "MessageTemplate: $template" }
            require(template.clazz != null) {
                "Message class is null for template: $template"
            }

            try {


                val message = template.clazz.createInstance() as Message
                // TODO: Read from the macro DynamicByteBuffer instead, but good enough for now
                val dataOffset = template.code.length
                val dataSize = data.size - dataOffset
                message.header.dataSize = dataSize
                message.readData(DataInputStream(ByteArrayInputStream(data, dataOffset, dataSize)), dataSize)

                return message
            } catch (err: Throwable) {
                log.error(err) { "Error creating message instance  (type=${template.code}): ${err.message}" }
                throw err
            }
        } catch (err: Exception) {
            log.error(err) { "Unable to parse message: ${err.message}" }
            return null
        } catch (err: Throwable) {
            // Never swallow an Error (e.g. OutOfMemoryError) into null — let it
            // propagate to parseBuffer's reset-and-rethrow path.
            log.error(err) { "Fatal error parsing message: ${err.message}" }
            throw err
        }
    }


    /**
     * Reset parser frame state. Call on disconnect/reconnect so a partially-read
     * frame size from a previous connection can't mis-frame the next stream. (The
     * parsed buffer is owned by the socket and is recreated per connection, so
     * only [pendingMessageSize] needs clearing here.)
     */
    fun reset() {
        pendingMessageSize = 0
    }

    companion object {

        const val MAX_MESSAGE_SIZE = 4 * 1024 * 1024

        	private val log = KLoggingManager.logger(MessageParser::class.java.simpleName)

    }
}