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

package org.tfv.deskflow.client.util

import java.util.concurrent.CopyOnWriteArrayList

open class SimpleEventEmitter<Payload> : ISimpleEventEmitter<Payload> {
    // CopyOnWriteArrayList makes on/off/clear/emit safe across the many threads
    // that touch the global ClientEventBus, and its snapshot iterator means a
    // once() listener removing itself during emit() no longer throws CME.
    private val listeners = CopyOnWriteArrayList<(Payload) -> Unit>()

    /** Register a listener for [event]. */
    override fun on(listener: (payload: Payload) -> Unit) {
        listeners.add(listener)
    }

    /** Register a one-time listener for [event]. */
    override fun once(listener: (payload: Payload) -> Unit) {
        val wrapper = createOnceWrapper(listener)
        on(wrapper)
    }

    private fun createOnceWrapper(listener: (payload: Payload) -> Unit): (payload: Payload) -> Unit {
        var wrapper: ((payload: Payload) -> Unit)? = null
        wrapper = { payload ->
            listener(payload)
            off(wrapper!!)
        }

        return wrapper
    }

    /** Remove a specific [listener] for [event]. */
    override fun off(listener: (payload: Payload) -> Unit) {
        listeners.run {
            remove(listener)
        }
    }

    /** Emit passing along an optional [payload]. */
    override fun emit(payload: Payload) {
        listeners.forEach { it(payload) }
    }

    /** Remove all listeners for [event], or all events if [event] is null. */
    override fun clear() {
        listeners.clear()
    }
}
