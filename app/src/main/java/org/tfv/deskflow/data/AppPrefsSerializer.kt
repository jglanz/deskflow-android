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

package org.tfv.deskflow.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import io.github.oshai.kotlinlogging.Level
import org.tfv.deskflow.client.models.SERVER_DEFAULT_ADDRESS
import org.tfv.deskflow.client.models.SERVER_DEFAULT_PORT
import org.tfv.deskflow.client.models.SERVER_DEFAULT_SCREEN_NAME
import org.tfv.deskflow.data.models.AppPrefs
import org.tfv.deskflow.data.models.AppPrefsKt.ScreenConfigKt.serverConfig
import org.tfv.deskflow.data.models.AppPrefsKt.loggingConfig
import org.tfv.deskflow.data.models.AppPrefsKt.screenConfig
import org.tfv.deskflow.data.models.copy
import java.io.InputStream
import java.io.OutputStream

object AppPrefsSerializer : Serializer<AppPrefs> {
    override val defaultValue: AppPrefs = AppPrefs.getDefaultInstance(
    ).copy {
        logging = loggingConfig {
            forwardingEnabled = true
            forwardingLevel = Level.INFO.name
        }

        screen = screenConfig {
            name = SERVER_DEFAULT_SCREEN_NAME
            server = serverConfig {
                address = SERVER_DEFAULT_ADDRESS
                port = SERVER_DEFAULT_PORT
                useTls = false
            }
        }

        mouseSensitivity = 1.0f
    }

    override suspend fun readFrom(input: InputStream): AppPrefs {
        try {
            return AppPrefs.parseFrom(input)
        } catch (e: Exception) {
            throw CorruptionException("Cannot read proto.", e)
        }
    }

    override suspend fun writeTo(t: AppPrefs, output: OutputStream) = t.writeTo(output)
}