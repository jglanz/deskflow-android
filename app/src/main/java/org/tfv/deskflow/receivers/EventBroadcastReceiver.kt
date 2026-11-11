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

package org.tfv.deskflow.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.ext.getSerializableExtraCompat

class EventBroadcastReceiver() : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_KEYBOARD -> {
                ClientEventBus.emit(
                    intent.getSerializableExtraCompat("payload", KeyboardEvent::class.java)!!
                )
            }
            ACTION_MOUSE -> {
                ClientEventBus.emit(
                    intent.getSerializableExtraCompat("payload", MouseEvent::class.java)!!
                )
            }
            else -> {
                log.warn { "Unsupported action ${intent.action}" }
            }
        }


    }

    companion object {
        const val ACTION_KEYBOARD = "org.tfv.deskflow.KEYBOARD_ACTION"
        const val ACTION_MOUSE = "org.tfv.deskflow.MOUSE_ACTION"


        private val log = KLoggingManager.logger(EventBroadcastReceiver::class.java.simpleName)

    }
}