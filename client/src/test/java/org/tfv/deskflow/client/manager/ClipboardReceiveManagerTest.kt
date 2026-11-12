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
package org.tfv.deskflow.client.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tfv.deskflow.client.io.msgs.ClipboardDataMessage
import org.tfv.deskflow.client.models.ClipboardDataMarker

/** Minimal clipboard-data message carrying only the marker byte (enough to drive the FSM). */
private fun markerMsg(marker: ClipboardDataMarker) =
  ClipboardDataMessage(data = byteArrayOf(marker.code.toByte()))

/**
 * Exercises the ClipboardReceiveManager receive state machine (phase transitions, reset on
 * mis-ordering, restart-on-double-start) without needing a fully-valid clipboard payload.
 */
class ClipboardReceiveManagerTest {

  @Test
  fun dataBeforeStartIsRejectedAndResets() {
    val mgr = ClipboardReceiveManager()
    mgr.submitMessage(markerMsg(ClipboardDataMarker.Data))
    assertTrue(mgr.messages.isEmpty(), "Data before Start must not accumulate")
    assertEquals(ClipboardDataMarker.Unknown, mgr.phase)
  }

  @Test
  fun endWithoutDataIsRejectedAndResets() {
    val mgr = ClipboardReceiveManager()
    mgr.submitMessage(markerMsg(ClipboardDataMarker.Start))
    mgr.submitMessage(markerMsg(ClipboardDataMarker.End))
    assertTrue(mgr.messages.isEmpty(), "End without Data must not generate / accumulate")
    assertEquals(ClipboardDataMarker.Unknown, mgr.phase)
  }

  @Test
  fun startThenDataAccumulates() {
    val mgr = ClipboardReceiveManager()
    mgr.submitMessage(markerMsg(ClipboardDataMarker.Start))
    assertTrue(mgr.isCollectingData)
    assertEquals(ClipboardDataMarker.Start, mgr.phase)
    mgr.submitMessage(markerMsg(ClipboardDataMarker.Data))
    assertEquals(1, mgr.dataMessageCount)
    assertTrue(mgr.isCollectingData)
  }

  @Test
  fun secondStartResetsAndRestarts() {
    val mgr = ClipboardReceiveManager()
    mgr.submitMessage(markerMsg(ClipboardDataMarker.Start))
    mgr.submitMessage(markerMsg(ClipboardDataMarker.Data))
    assertEquals(1, mgr.dataMessageCount)
    // A second Start drops the in-flight transfer and begins a new one.
    mgr.submitMessage(markerMsg(ClipboardDataMarker.Start))
    assertEquals(0, mgr.dataMessageCount)
    assertEquals(1, mgr.messages.size)
    assertEquals(ClipboardDataMarker.Start, mgr.phase)
  }
}
