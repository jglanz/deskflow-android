package org.tfv.deskflow.services.shizuku

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.tfv.deskflow.client.util.logging.KLoggingManager

/**
 * Turns Deskflow mouse primitives into a real mouse MotionEvent stream
 * (SOURCE_MOUSE / TOOL_TYPE_MOUSE) and injects it through [ShizukuInputBridge].
 *
 * The sequence mirrors what InputReader's CursorInputMapper produces for a
 * physical mouse, so apps get hover, context clicks, native wheel scrolling and
 * mouse-style drag / text selection:
 *
 *   HOVER_MOVE* → DOWN → BUTTON_PRESS → MOVE* → BUTTON_RELEASE → UP → HOVER_MOVE
 *
 * HOVER_ENTER/EXIT are synthesized per window by InputDispatcher.
 *
 * Not thread-safe: call from one thread (GlobalInputService's main handler).
 */
class ShizukuMouseInjector(private val bridge: ShizukuInputBridge = ShizukuInputBridge) {
  private val log = KLoggingManager.logger("ShizukuMouseInjector")

  private var x = 0f
  private var y = 0f
  private var buttonState = 0
  private var downTime = 0L

  var displayId: Int = 0

  val isReady: Boolean
    get() = bridge.service != null

  fun moveTo(newX: Float, newY: Float, metaState: Int = 0) {
    x = newX
    y = newY
    inject(
      if (buttonState != 0) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE,
      metaState = metaState,
    )
  }

  /** @param button A MotionEvent.BUTTON_* constant. */
  fun buttonDown(button: Int, metaState: Int = 0) {
    if (buttonState and button != 0) return
    val wasUp = buttonState == 0
    buttonState = buttonState or button
    if (wasUp) {
      downTime = SystemClock.uptimeMillis()
      inject(MotionEvent.ACTION_DOWN, metaState = metaState)
    }
    inject(MotionEvent.ACTION_BUTTON_PRESS, actionButton = button, metaState = metaState)
  }

  /** @param button A MotionEvent.BUTTON_* constant. */
  fun buttonUp(button: Int, metaState: Int = 0) {
    if (buttonState and button == 0) return
    buttonState = buttonState and button.inv()
    inject(MotionEvent.ACTION_BUTTON_RELEASE, actionButton = button, metaState = metaState)
    if (buttonState == 0) {
      inject(MotionEvent.ACTION_UP, metaState = metaState)
      inject(MotionEvent.ACTION_HOVER_MOVE, metaState = metaState)
    }
  }

  /** Wheel deltas in detents (1.0 = one notch); positive v = up, positive h = right. */
  fun scroll(h: Float, v: Float, metaState: Int = 0) {
    inject(MotionEvent.ACTION_SCROLL, hScroll = h, vScroll = v, metaState = metaState)
  }

  /** Release held buttons, e.g. when the cursor leaves this screen or on disconnect. */
  fun releaseAll() {
    if (buttonState == 0) return
    log.debug { "Releasing held buttons: 0x${buttonState.toString(16)}" }
    for (bit in 0 until 31) {
      val button = 1 shl bit
      if (buttonState and button != 0) buttonUp(button)
    }
  }

  private fun inject(
    action: Int,
    actionButton: Int = 0,
    hScroll: Float = 0f,
    vScroll: Float = 0f,
    metaState: Int = 0,
  ) {
    val svc = bridge.service ?: return
    val now = SystemClock.uptimeMillis()
    val props =
      MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_MOUSE
      }
    val coords =
      MotionEvent.PointerCoords().apply {
        x = this@ShizukuMouseInjector.x
        y = this@ShizukuMouseInjector.y
        pressure = if (buttonState != 0) 1f else 0f
        size = 1f
        if (action == MotionEvent.ACTION_SCROLL) {
          setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
          setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        }
      }
    // Events belonging to a pressed gesture share the DOWN's downTime.
    val inPressedStream =
      buttonState != 0 ||
        action == MotionEvent.ACTION_UP ||
        action == MotionEvent.ACTION_BUTTON_RELEASE
    val event =
      MotionEvent.obtain(
        if (inPressedStream) downTime else now,
        now,
        action,
        1,
        arrayOf(props),
        arrayOf(coords),
        metaState,
        buttonState,
        1f,
        1f,
        DEVICE_ID,
        0,
        InputDevice.SOURCE_MOUSE,
        0,
      )
    try {
      svc.injectMotionEvent(event, displayId, actionButton)
    } catch (err: Exception) {
      log.error(err) { "Shizuku inject failed" }
    } finally {
      event.recycle()
    }
  }

  companion object {
    /** Same as scrcpy: injected events not tied to a real input device. */
    private const val DEVICE_ID = 0

    /** Map a Deskflow button id to a MotionEvent button, or null if not a pointer button. */
    fun mapDeskflowButton(id: Int): Int? =
      when (id) {
        1 -> MotionEvent.BUTTON_PRIMARY
        2 -> MotionEvent.BUTTON_TERTIARY
        3 -> MotionEvent.BUTTON_SECONDARY
        else -> null
      }
  }
}
