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

package org.tfv.deskflow.client.cli

import org.tfv.deskflow.client.util.logging.KLoggingManager

import org.tfv.deskflow.client.Client
import org.tfv.deskflow.client.models.SERVER_DEFAULT_SCREEN_NAME
import org.tfv.deskflow.client.models.ServerTarget

import java.lang.Thread.sleep
import java.net.InetAddress
import java.net.UnknownHostException

private val log = KLoggingManager.logger("org.tfv.deskflow.client.cli.Main")

fun main() {
    val localHostName = try {
        InetAddress.getLocalHost().hostName
    } catch (err: UnknownHostException) {
        log.error(err) {
            "Unable to resolve local hostname via InetAddress.getLocalHost(); " +
                "the server needs a resolvable screen name to register this client. " +
                "Ensure this host's name resolves (e.g. add an entry to /etc/hosts) and retry."
        }
        System.exit(1)
        return
    }

    val serverTarget = ServerTarget(
        SERVER_DEFAULT_SCREEN_NAME,
        localHostName,
        24800,
        false,
        1920,
        1080
    )
    log.info { "Starting client: $serverTarget}" }

    val client = Client()
    client.setTarget(serverTarget)

    // setTarget() runs asynchronously on the client's connection executor: it
    // returns before the target is applied or connect() is invoked. waitForSocket()
    // is a no-op while the socket is still null, so calling it immediately lets
    // main() exit before the connection is even attempted — the old fixed
    // Thread.sleep(100) was a fragile race against that. Client exposes no
    // completion future/callback/latch, so poll the only public connection-state
    // observable (isConnected) for a short grace window; once the socket is live
    // waitForSocket() has something to actually wait on.
    val pollDeadline = System.currentTimeMillis() + 5000L
    while (!client.isConnected && System.currentTimeMillis() < pollDeadline) {
        sleep(50)
    }
    if (!client.isConnected) {
        log.warn { "Socket did not report connected within 5s grace window; calling waitForSocket() anyway" }
    }

    client.waitForSocket()
}