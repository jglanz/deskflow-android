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



package org.tfv.deskflow.logging

import android.util.Log

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker
import org.tfv.deskflow.BuildConfig
import org.tfv.deskflow.client.ClientEventBus
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class AndroidForwardingLogger(override val name: String) : KLogger {
    companion object {
        private val forwardingEnabledStorage = AtomicBoolean(true)

        var forwardingEnabled: Boolean
            get() = forwardingEnabledStorage.load()
            set(value)= forwardingEnabledStorage.store(value)

        fun setForwardingEnabled(enabled: Boolean): Boolean {
            return forwardingEnabledStorage.exchange(enabled)
        }

        private val forwardingLevelStorage = AtomicReference(if (BuildConfig.DEBUG) Level.TRACE else Level.INFO)
        var forwardingLevel: Level
            get() = forwardingLevelStorage.load()
            set(value) = forwardingLevelStorage.store(value)
    }

    internal fun forwardLogEvent(level: Level, tag: String,  message: String, cause: Throwable? = null, timestamp: Long = System.currentTimeMillis()) {
        if (!forwardingEnabled || level < forwardingLevel) {
            return
        }

        ClientEventBus.emit(LogRecordEvent(
            level = level,
            tag = tag,
            message = message,
            cause = cause,
            timestamp = timestamp
        ))

    }

    override fun at(level: Level, marker: Marker?, block: KLoggingEventBuilder.() -> Unit) {
        if (isLoggingEnabledFor(level, marker)) {
            KLoggingEventBuilder().apply(block).run {
                when (level) {
                    Level.TRACE -> Log.v(name, this.message, this.cause)
                    Level.DEBUG -> Log.d(name, this.message, this.cause)
                    Level.INFO -> Log.i(name, this.message, this.cause)
                    Level.WARN -> Log.w(name, this.message, this.cause)
                    Level.ERROR -> Log.e(name, this.message, this.cause)
                    Level.OFF -> Unit
                }

                forwardLogEvent(level, name, this.message ?: "<NULL>", this.cause)
            }
        }
    }

    override fun isLoggingEnabledFor(level: Level, marker: Marker?): Boolean {
        // Honor the user-selected forwarding level (the in-app Trace/Debug
        // selector), not Log.isLoggable — which is false for DEBUG/VERBOSE by
        // default and would silently drop those logs regardless of the selection.
        // (Level ordinals: TRACE < DEBUG < INFO < WARN < ERROR < OFF.)
        return level != Level.OFF && level >= forwardingLevel
    }
}
