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

package org.tfv.deskflow.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import arrow.core.raise.catch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.tfv.deskflow.R
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.components.GlobalKeyboardManager
import org.tfv.deskflow.ext.canDrawOverlays
import org.tfv.deskflow.ext.getScreenSize
import org.tfv.deskflow.ext.sendServiceConnectionEvent

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@SuppressLint("ServiceCast")
class GlobalInputService : AccessibilityService() {

  private var pickerShownForPackage: String? = null

  /** Client responsible for communicating with the connection service. */
  private lateinit var serviceClient: ConnectionServiceClient

  /**
   * Keyboard manager for handling keyboard events, calculating state and
   * triggering actions.
   */
  private lateinit var keyboardManager: GlobalKeyboardManager

  private val clipboard by lazy {
    getSystemService(ClipboardManager::class.java)
  }

  private val imeManager by lazy {
    getSystemService(InputMethodManager::class.java)
  }

  private val notificationManager by lazy {
    getSystemService(NotificationManager::class.java)
  }

  private val powerManager by lazy {
    getSystemService(PowerManager::class.java)
  }

  /**
   * Flow to observe if the home screen is currently active. This is used to
   * determine if the current screen is the home screen.
   * > Example: Used for checking if the user is on the home screen in the
   * > global input service.
   */
  private val isHomeScreenActiveFlow = MutableStateFlow(false)

  /** Check if the home screen is currently active. */
  private val isHomeScreenActive: Boolean
    get() = isHomeScreenActiveFlow.value

  /**
   * Set of known home packages. Populated when the service is connected via
   * `fetchHomePackages`.
   */
  private val knownHomePackages = mutableSetOf<String>()

  private val activePackageName: String?
    get() = rootInActiveWindow?.packageName?.toString()

  private val keyboardWindowInfo: AccessibilityWindowInfo?
    get() =
      windows.find { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

  private val isKeyboardOpened: Boolean
    get() = keyboardWindowInfo != null

  /**
   * Flag to indicate if a global input action is currently pending. This is
   * used to prevent multiple gestures from being dispatched at the same time.
   */
  @Volatile private var globalInputPending = false

  /**
   * Handler for posting global input actions to the main thread. This is used
   * to ensure that gestures are dispatched on the main thread.
   */
  private val globalInputHandler = Handler(Looper.getMainLooper())

  /**
   * Callback for gesture results. This is used to handle the completion or
   * cancellation of gestures.
   */
  private val gestureResultCallback =
    object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        super.onCompleted(gestureDescription)
        globalInputPending = false
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        super.onCancelled(gestureDescription)
        globalInputPending = false
      }
    }

  /**
   * Layout parameters for the mouse pointer view. This is used to position the
   * mouse pointer on the screen.
   */
  private lateinit var mousePointerLayout: WindowManager.LayoutParams

  /**
   * View for the mouse pointer. This is the view that will be displayed on the
   * screen to represent the mouse pointer.
   */
  private lateinit var mousePointerView: View

  /**
   * Flag to indicate if the mouse pointer is currently visible. This is used to
   * control the visibility of the mouse pointer view.
   */
  @Volatile private var mousePointerVisible = false

  /**
   * Authoritative pointer position, in pixels.
   *
   * Kept separate from [mousePointerLayout] because the layout is only pushed to
   * the window manager on a throttle (see [schedulePointerApply]), so
   * between frames the layout holds a stale position. Relative moves and clicks
   * read these instead, or they would compound against that stale value.
   *
   * Seeded to the same coordinates the layout is initialised with.
   */
  private var pointerX = 200

  private var pointerY = 200

  /** uptimeMillis of the last pointer layout push, for the update throttle. */
  private var lastPointerApplyTime = 0L

  /** Whether a trailing-edge pointer push is already queued. */
  private var trailingApplyScheduled = false

  /** Whether the pointer view is currently shown (mouse is on this screen). */
  private var pointerShown = false

  /**
   * Interval between pointer layout pushes, in ms.
   *
   * This is the rate at which the pointer *window position* is pushed to the
   * compositor -- NOT the rate at which incoming mouse events are processed.
   * Every event updates the logical pointerX/pointerY immediately and uncapped
   * (native mouse polling is 125Hz-8000Hz), so clicks, drags and gestures always
   * use the freshest position; this throttle only governs the on-screen redraw.
   *
   * Derived from the display refresh rate but clamped to [250Hz, 125Hz] worth of
   * period. Pushing faster than the panel refreshes doesn't add visible frames --
   * the compositor still only latches one position per vsync -- but it keeps the
   * position it latches fresher, shaving input latency. The 125Hz floor is why we
   * don't just ride vsync: this panel is 60Hz, and 60Hz coalescing leaves the
   * latched position up to ~16ms stale. The 250Hz cap bounds the per-push
   * WindowManager relayout cost so a high-refresh panel can't run it away.
   */
  private val pointerApplyIntervalMs: Long by lazy {
    val refreshHz =
      try {
        getSystemService(DisplayManager::class.java)
          .getDisplay(Display.DEFAULT_DISPLAY)
          ?.refreshRate ?: 60f
      } catch (err: Exception) {
        60f
      }
    val hz = refreshHz.roundToInt().coerceIn(POINTER_MIN_HZ, POINTER_MAX_HZ)
    1000L / hz
  }

  /** Pointer position captured at button-down, used for the click/drag split. */
  private var dragStartX = 0

  private var dragStartY = 0

  /*
   * Live drag as a continued gesture.
   *
   * A drag has to happen *live* -- content following the cursor as it moves --
   * not replayed on release. The accessibility API can't inject raw touch
   * events, but StrokeDescription with willContinue=true does the equivalent: the
   * first stroke presses and holds the touch, each continueStroke() extends it in
   * real time, and a final willContinue=false stroke lifts it. That maps exactly
   * onto mouse down -> moves -> up.
   *
   * Only one gesture can be in flight at a time, so strokes are paced by the
   * completion callback: the next segment (to wherever the cursor is now) is
   * dispatched when the previous one finishes. Between segments the held touch
   * stays put, so pausing mid-drag holds rather than lifts.
   */
  private var dragButtonDown = false

  private var dragStrokeStarted = false

  private var dragEndPending = false

  private var dragStrokeInFlight = false

  private var lastStroke: StrokeDescription? = null

  /** Endpoint of the last dispatched stroke; the next segment starts here. */
  private var lastDragX = 0

  private var lastDragY = 0

  /** Most recent movement delta, used to carry momentum into the release. */
  private var dragLastDx = 0

  private var dragLastDy = 0

  /** Display bounds captured at button-down, to keep strokes on-screen. */
  private var dragBoundW = 0

  private var dragBoundH = 0

  private val dragGestureCallback =
    object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription) {
        dragStrokeInFlight = false
        advanceDrag()
      }

      override fun onCancelled(gestureDescription: GestureDescription) {
        // The system dropped the gesture (e.g. another took over); abandon the
        // chain rather than leave a touch half-held.
        endDragChain()
      }
    }

  /**
   * Movement below this (px) is a click, not a drag. Uses the platform touch
   * slop so a slightly jittery press still registers as a tap, matching how the
   * framework itself distinguishes the two.
   */
  private val touchSlop by lazy {
    ViewConfiguration.get(this).scaledTouchSlop
  }

  /**
   * Push the latest pointer position to the window manager.
   *
   * [WindowManager.updateViewLayout] is a synchronous round trip to
   * WindowManagerService that forces a relayout, so it must not run per incoming
   * event -- Deskflow streams moves far faster than that. Callers reach it
   * through [schedulePointerApply], which throttles to [pointerApplyIntervalMs].
   */
  private fun applyPointerLayout() {
    if (!mousePointerVisible) return
    mousePointerLayout.x = pointerX
    mousePointerLayout.y = pointerY
    windowManager.updateViewLayout(mousePointerView, mousePointerLayout)
    // Reveal only now that a real position has been pushed, so re-entry (and the
    // first move after a wake) never flashes the pointer at its stale exit spot.
    if (!pointerShown) {
      mousePointerView.visibility = View.VISIBLE
      pointerShown = true
    }
  }

  /** Hide the pointer view without detaching its window; see [applyPointerLayout]. */
  private fun hideMousePointer() {
    if (!pointerShown) return
    mousePointerView.visibility = View.INVISIBLE
    pointerShown = false
  }

  private val trailingApplyRunnable = Runnable {
    trailingApplyScheduled = false
    lastPointerApplyTime = SystemClock.uptimeMillis()
    applyPointerLayout()
  }

  /**
   * Throttle pointer pushes to [pointerApplyIntervalMs], leading + trailing edge.
   *
   * An isolated move applies immediately (leading edge -- no added latency beyond
   * the next vsync). During a burst, intermediate moves only update pointerX/Y
   * and a single trailing push is queued for the end of the interval, so the many
   * events between two pushes collapse into one relayout carrying the newest
   * position. Idle costs nothing: no move, no wakeup.
   */
  private fun schedulePointerApply() {
    val now = SystemClock.uptimeMillis()
    val elapsed = now - lastPointerApplyTime
    if (elapsed >= pointerApplyIntervalMs) {
      lastPointerApplyTime = now
      applyPointerLayout()
    } else if (!trailingApplyScheduled) {
      trailingApplyScheduled = true
      globalInputHandler.postDelayed(
        trailingApplyRunnable,
        pointerApplyIntervalMs - elapsed,
      )
    }
  }

  private val keyboardWasOpen = AtomicBoolean(false)

  /** WindowManager instance for managing the mouse pointer view. */
  private lateinit var windowManager: WindowManager

  private val serviceScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun createNotificationChannel() {

    val name =
      resources.getText(R.string.global_input_service_notification_channel_name)
    val desc =
      resources.getText(
        R.string.global_input_service_notification_channel_description
      )
    val importance = NotificationManager.IMPORTANCE_DEFAULT
    val chan =
      NotificationChannel(CHANNEL_ID, name, importance).apply {
        description = desc.toString()
      }
    notificationManager.createNotificationChannel(chan)
  }

  private fun sendStatusNotification(
    text: String,
    customizer: (NotificationCompat.Builder.() -> Unit)? = null,
  ) {
    // POST_NOTIFICATIONS only became a runtime permission in API 33. Older
    // platforms don't define it, so checkSelfPermission reports DENIED for a
    // permission that is never actually required there -- gating on it
    // unconditionally would silence every notification on API 31/32.
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
          this,
          Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
      log.warn {
        "POST_NOTIFICATIONS permission not granted, cannot send notification."
      }
      return
    }
    val notificationBuilder =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.deskflow_icon_fit) // your icon
        .setContentTitle(
          resources.getText(
            R.string.global_input_service_notification_channel_name
          )
        )
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
    if (customizer != null) {
      customizer(notificationBuilder)
    }

    val notification = notificationBuilder.build()
    // TODO: Add action to open keyboard settings
    NotificationManagerCompat.from(this)
      .notify(NOTIF_IME_NOT_SETUP_ID, notification)
  }

  private fun isDeskflowKeyboardActive(
    imeInfo: InputMethodInfo? = currentImeInfoCompat
  ): Boolean {
    return imeInfo?.let { current -> deskflowImeInfo?.id == current.id }
      ?: false
  }

  /**
   * The IME the user currently has selected, or null if it can't be resolved.
   *
   * [InputMethodManager.getCurrentInputMethodInfo] is API **34**+, not 33 as its
   * placement might suggest -- lint's API database is the authority here, and it
   * flagged an earlier version of this guard that used 33. On anything older the
   * method does not exist and invoking it throws NoSuchMethodError, which crashed
   * this service on every accessibility event it handled. Below 34 we resolve the
   * same value the way the platform stores it -- Settings.Secure
   * DEFAULT_INPUT_METHOD holds the flattened ComponentName of the active IME --
   * and match that against the installed list.
   */
  private val currentImeInfoCompat: InputMethodInfo?
    get() =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        imeManager.currentInputMethodInfo
      } else {
        val activeId =
          android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
          )
        activeId?.let { id -> imeManager.inputMethodList.find { it.id == id } }
      }

  private fun checkIMESetup() {
    val kbOpen = isKeyboardOpened
    val kbWasOpen = keyboardWasOpen.load()
    if (!kbOpen) {
      keyboardWasOpen.store(false)
      return
    }

    if (kbWasOpen) return

    if (!serviceClient.state.isConnected) return

    keyboardWasOpen.store(true)

    val imeInfo = currentImeInfoCompat
    if (imeInfo != null) {
      log.debug { "Current IME: ${imeInfo.packageName}" }
      when {
        isDeskflowKeyboardActive(imeInfo) -> {
          log.debug { "Current IME is deskflow, no action needed." }
        }
        !isDeskflowImeEnabled -> {
          log.warn { "IME is not enabled, showing notification." }
          sendStatusNotification(
            resources.getString(
              R.string.global_input_service_notification_ime_not_setup
            )
          ) {
            setContentIntent(
              PendingIntent.getActivity(
                this@GlobalInputService,
                0,
                Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE,
              )
            )
          }
        }
        with(activePackageName) {
          this == null || pickerShownForPackage == this
        } -> {
          log.debug {
            "IME picker was already shown for package $pickerShownForPackage"
          }
        }
        else -> {
          log.debug {
            "IME is enabled, but not active. Previous picker was shown for package $pickerShownForPackage"
          }

          pickerShownForPackage = activePackageName
          val imeId = deskflowImeInfo?.id
          if (
            imeId != null && softKeyboardController.switchToInputMethod(imeId)
          ) {
            log.debug { "softKeyboardController set IME to $imeId" }
            return
          }

          imeManager.showInputMethodPicker()
        }
      }
    } else {
      log.warn { "No current IME found." }
    }
  }

  @SuppressLint("RtlHardcoded")
  override fun onCreate() {
    super.onCreate()
    log.debug { "onCreate:${GlobalInputService::class.simpleName}" }

    createNotificationChannel()

    keyboardManager = GlobalKeyboardManager(this)
    serviceScope.launch {
      keyboardManager.actionFlow.collect { action ->
        log.debug { "Triggered Action: ${action.label}(${action.actionId})" }
        when (action.actionId) {
          GLOBAL_ACTION_DPAD_CENTER -> {
            clickFocused()
          }

          else -> {
            performGlobalAction(action.actionId)
          }
        }
      }
    }

    serviceClient =
      ConnectionServiceClient(this) { event ->
        globalInputHandler.post {
          try {
            when (event) {
              is MouseEvent -> {
                onMouseEvent(event)
              }

              is KeyboardEvent -> {
                onKeyboardEvent(event)
              }

              is ScreenEvent.SetClipboard -> {
                val data = event.data
                log.debug { "SetClipboard(variantCount=${data.variants.size})" }
                val knownFormats =
                  arrayOf(
                    ClipboardData.Format.Text // ClipboardData.Format.Bitmap
                  )

                for (format in knownFormats) {
                  val variant = data.variants[format] ?: continue

                  // TODO: Implement `Converter` concept
                  val clipData =
                    when (format) {
                      ClipboardData.Format.Text -> {
                        log.debug { "SetClipboard: Text(${variant.data.size})" }
                        val text = String(variant.data, Charsets.UTF_8)
                        ClipData.newPlainText("deskflow_clipboard", text)
                      }

                      else -> {
                        log.warn {
                          "SetClipboard: No converter for format $format"
                        }
                        continue
                      }
                    }

                  log.debug { "Clipboard variant ready: $variant" }
                  clipboard.setPrimaryClip(clipData)
                  break
                }
              }

              else -> {}
            }
          } catch (err: Exception) {
            log.error(err) {
              "Error running on globalInputHandler: ${err.message}"
            }
          }
        }
      }

    mousePointerView = View.inflate(baseContext, R.layout.mouse_pointer, null)
    mousePointerLayout =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
      )

    mousePointerLayout.layoutInDisplayCutoutMode =
      LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    mousePointerLayout.gravity = Gravity.TOP or Gravity.LEFT
    mousePointerLayout.x = 200
    mousePointerLayout.y = 200

    // Start hidden: the pointer should only appear once the server's mouse has
    // actually crossed onto this screen and given us a real position. Revealed
    // by the first move after that (see moveMousePointer).
    mousePointerView.visibility = View.INVISIBLE

    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    // Hide the pointer whenever the mouse isn't on this screen. The server only
    // streams moves while its cursor is here (screen.isActive), and leaving
    // otherwise strands a stale cursor at the exit edge -- which then flashes
    // back on the next wake before fresh moves arrive.
    serviceScope.launch {
      serviceClient.stateFlow
        .map { it.screen.isActive }
        .distinctUntilChanged()
        .collect { active ->
          if (!active) globalInputHandler.post { hideMousePointer() }
        }
    }

    startService(Intent(applicationContext, ConnectionService::class.java))
    serviceClient.bind()
  }

  /**
   * Handle keyboard events from the client. This is where we process keyboard
   * events and pass them to the keyboard manager.
   * > Example: Used for handling key presses in the global input service.
   */
  private fun onKeyboardEvent(event: KeyboardEvent) {
    log.debug { "onKeyboardEvent: $event" }
    keyboardManager.process(event)
  }

  /**
   * Called when the service is destroyed. This is where we clean up resources
   * and remove the mouse pointer view.
   */
  override fun onDestroy() {
    serviceScope.cancel()
    serviceClient.unbind()
    // Only added if the overlay permission was held at onServiceConnected time,
    // so removing unconditionally throws IllegalArgumentException ("not attached
    // to window manager") and turns a clean shutdown into a crash.
    if (mousePointerVisible) {
      windowManager.removeView(mousePointerView)
      mousePointerVisible = false
    }
    super.onDestroy()
  }

  /**
   * Perform a click on the closest node to the mouse pointer. This is used to
   * simulate a mouse click on the screen.
   * > Example: Used for clicking on buttons or input fields in the global input
   * > service.
   */
  @Suppress("unused")
  private fun clickClosestNodeToPointer() {
    val nodeInfo = rootInActiveWindow ?: return
    val nearestNodeToMouse: AccessibilityNodeInfo? =
      findSmallestNodeAtPoint(nodeInfo, pointerX, pointerY)
    if (nearestNodeToMouse != null) {
      logNodeHierarchy(nearestNodeToMouse, 0)
      nearestNodeToMouse.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
  }

  /**
   * Perform a click gesture at the current mouse pointer position. This is used
   * to simulate a mouse click on the screen.
   * > Example: Used for clicking on buttons or input fields in the global input
   * > service.
   */
  private fun clickGesture(
    x: Float = pointerX.toFloat(),
    y: Float = pointerY.toFloat(),
  ) {

    log.debug { "Click gesture at [$x, $y]" }

    val path = Path().apply { moveTo(x, y) }
    val gesture =
      GestureDescription.Builder()
        .addStroke(StrokeDescription(path, 0, 100))
        .build()
    dispatchGesture(gesture, gestureResultCallback, globalInputHandler)
  }

  /**
   * Move the mouse pointer to the specified coordinates. This is used to update
   * the position of the mouse pointer on the screen.
   * > Example: Used for mouse movement in the global input service.
   */
  private fun moveMousePointer(x: Int, y: Int) {
    if (!mousePointerVisible) return

    // getScreenSize() allocates three objects per call. Kept inside the lambda
    // so that cost is paid only when debug logging is actually enabled -- the
    // log message is lazy, but an argument evaluated outside it is not.
    log.debug {
      val screenSize = getScreenSize()
      "Cursor move to [${x}, ${y}] with size(${screenSize.px.width},${screenSize.px.height})"
    }

    pointerX = x
    pointerY = y
    // Feed the live drag: if the button is down and no stroke is mid-flight, push
    // a segment to the new position now. Otherwise the completion callback picks
    // it up when the current stroke finishes.
    if (dragButtonDown && !dragStrokeInFlight) advanceDrag()
    schedulePointerApply()
  }

  private fun clampX(x: Int) = x.coerceIn(0, (dragBoundW - 1).coerceAtLeast(0))

  private fun clampY(y: Int) = y.coerceIn(0, (dragBoundH - 1).coerceAtLeast(0))

  /**
   * Advance the live drag one segment: press on the first move, extend toward the
   * cursor on each subsequent move, and lift once the button is up.
   *
   * Runs only when no stroke is in flight (the accessibility system dispatches
   * one gesture at a time). Called from a move, or from the completion callback
   * as each segment finishes.
   */
  private fun advanceDrag() {
    if (dragStrokeInFlight) return
    // Once the button is up and no stroke is live, there is nothing to advance.
    // Without this, the release lift's own completion callback re-enters the
    // start branch below -- the cursor is still far from the (stale) down point,
    // so it presses a phantom touch with no button held, which reads as "never
    // let go": the drag re-grabs and only a fresh click breaks it.
    if (!dragButtonDown && !dragStrokeStarted) return
    val cx = clampX(pointerX)
    val cy = clampY(pointerY)

    if (!dragStrokeStarted) {
      // Don't press until the cursor leaves the down point by more than touch
      // slop -- within slop it's a tap (jitter and all), handled on Up via
      // clickGesture. The press starts at the down point and reaches the cursor.
      val sx = clampX(dragStartX)
      val sy = clampY(dragStartY)
      if (hypot((cx - sx).toFloat(), (cy - sy).toFloat()) < touchSlop) return
      lastStroke = StrokeDescription(segmentPath(sx, sy, cx, cy), 0, DRAG_STROKE_MS, true)
      dragLastDx = cx - sx
      dragLastDy = cy - sy
      lastDragX = cx
      lastDragY = cy
      dragStrokeStarted = true
      dispatchDragStroke(isFinal = false)
      return
    }

    val moved = cx != lastDragX || cy != lastDragY
    when {
      dragEndPending -> {
        // Release: reach the current point AND carry one more step of the recent
        // velocity, over a short interval, so the touch-up has real momentum.
        // Velocity-sensitive system gestures (app drawer, home, recents) commit
        // on release velocity -- lifting with none, or nudging backwards, makes
        // them snap shut no matter how far you dragged.
        val ex = clampX(cx + dragLastDx)
        val ey = clampY(cy + dragLastDy)
        val path =
          if (ex != lastDragX || ey != lastDragY) {
            segmentPath(lastDragX, lastDragY, ex, ey)
          } else {
            // No momentum to carry: lift with a 1px nudge in the drag direction
            // (never backwards), since a reversing nudge cancels the gesture.
            segmentPath(lastDragX, lastDragY, lastDragX, (lastDragY - 1).coerceAtLeast(0))
          }
        lastStroke = lastStroke?.continueStroke(path, 0, RELEASE_FLICK_MS, false)
        dragStrokeStarted = false
        dispatchDragStroke(isFinal = true)
      }
      moved -> {
        dragLastDx = cx - lastDragX
        dragLastDy = cy - lastDragY
        lastStroke =
          lastStroke?.continueStroke(segmentPath(lastDragX, lastDragY, cx, cy), 0, DRAG_STROKE_MS, true)
        lastDragX = cx
        lastDragY = cy
        dispatchDragStroke(isFinal = false)
      }
      // else: not moved, not ending -> touch holds in place, nothing to dispatch.
    }
  }

  private fun dispatchDragStroke(isFinal: Boolean) {
    val stroke = lastStroke
    if (stroke == null) {
      endDragChain()
      return
    }
    dragStrokeInFlight = true
    try {
      val gesture = GestureDescription.Builder().addStroke(stroke).build()
      val dispatched =
        dispatchGesture(gesture, dragGestureCallback, globalInputHandler)
      if (!dispatched) {
        dragStrokeInFlight = false
        endDragChain()
      }
    } catch (err: Exception) {
      log.warn(err) { "Failed to dispatch drag stroke" }
      dragStrokeInFlight = false
      endDragChain()
    }
    if (isFinal) {
      // The lift is on its way; the chain is done once this completes.
      lastStroke = null
      dragEndPending = false
    }
  }

  /** Abandon the drag chain and reset all of its state. */
  private fun endDragChain() {
    dragButtonDown = false
    dragStrokeStarted = false
    dragEndPending = false
    dragStrokeInFlight = false
    lastStroke = null
  }

  private fun segmentPath(x0: Int, y0: Int, x1: Int, y1: Int): Path =
    Path().apply {
      moveTo(x0.toFloat(), y0.toFloat())
      lineTo(x1.toFloat(), y1.toFloat())
    }

  /**
   * Move the cursor to the specified coordinates. This is used to move the
   * mouse pointer to a specific location on the screen.
   * > Example: Used for mouse movement in the global input service.
   */
  /**
   * Turn the screen on if it is currently off.
   *
   * SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP is the only route to wake the
   * display from a background context (the modern Activity flags need a visible
   * activity, which this service isn't). Both are deprecated but still the
   * supported mechanism for exactly this case. We acquire with a short timeout
   * and ON_AFTER_RELEASE, so the lock self-releases and hands control back to the
   * normal screen-off timer rather than pinning the display on.
   *
   * Guarded by isInteractive so it is a cheap no-op while the screen is already
   * on -- which it is for all but the first event after the device sleeps.
   *
   * Caveat: once the device is in deep Doze the TCP connection is frozen, so no
   * event arrives to trigger this until a maintenance window. Wake-on-mouse is
   * therefore reliable right after the screen turns off or while charging, not
   * after long idle on battery.
   */
  @Suppress("DEPRECATION")
  private fun wakeScreenIfAsleep() {
    if (powerManager.isInteractive) return
    try {
      powerManager
        .newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
          "deskflow:mouse-wake",
        )
        .acquire(WAKE_ON_INPUT_MS)
    } catch (err: Exception) {
      log.warn(err) { "Failed to wake screen" }
    }
  }

  private fun onMouseEvent(event: MouseEvent) {
    // Any mouse activity from the server means the user is reaching for this
    // screen -- wake it if it's asleep so the pointer isn't moving on a dark
    // panel.
    wakeScreenIfAsleep()
    when (event.type) {
      MouseEvent.Type.Move -> {
        moveMousePointer(event.x, event.y)
      }

      MouseEvent.Type.MoveRelative -> {
        moveMousePointer(pointerX + event.x, pointerY + event.y)
      }

      MouseEvent.Type.Down -> {
        log.debug { "Down [$event]" }
        // Down/Up carry no coordinates in the protocol -- position lives only in
        // the Move stream -- so the current pointer position IS the press point.
        dragStartX = pointerX
        dragStartY = pointerY
        // Bounds captured once here (getScreenSize allocates); every stroke point
        // is clamped to them, since dispatchGesture rejects an out-of-bounds path.
        val size = getScreenSize().px
        dragBoundW = size.width
        dragBoundH = size.height
        // Arm the drag. The touch isn't pressed until the cursor actually moves
        // (see advanceDrag); a down/up with no move stays a plain tap.
        dragButtonDown = true
        dragStrokeStarted = false
        dragEndPending = false
      }

      MouseEvent.Type.Up -> {
        log.debug { "Up [$event]" }
        dragButtonDown = false
        if (dragStrokeStarted) {
          // A live drag is in progress: lift the held touch at the release point.
          dragEndPending = true
          if (!dragStrokeInFlight) advanceDrag()
        } else {
          // Never moved off the down point -> it was a tap.
          clickGesture()
        }
      }

      MouseEvent.Type.Wheel -> {
        if (abs(event.y) < 240) return
        log.debug { "Wheel [${event.x}, ${event.y}]" }
        swipe(up = event.y > 0)
      }
    }
  }

  /** Set up the pointer view and add it to the window manager. */
  private fun setupMousePointer() {
    when (canDrawOverlays()) {
      true -> {
        if (!mousePointerVisible) {
          windowManager.addView(mousePointerView, mousePointerLayout)
          mousePointerVisible = true
        }
      }

      false -> {
        log.warn { "Overlay permissions not granted yet" }
      }
    }
  }

  /**
   * Called when the service is started. This is where we set the service to be
   * sticky and return START_STICKY.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }

  val deskflowImeInfo: InputMethodInfo?
    get() =
      imeManager.inputMethodList.find {
        it.packageName.lowercase().contains("deskflow")
      }

  val isDeskflowImeEnabled: Boolean
    get() =
      imeManager.enabledInputMethodList.any { it.id == deskflowImeInfo?.id }

  private val onClipboardChanged =
    object : ClipboardManager.OnPrimaryClipChangedListener {
      override fun onPrimaryClipChanged() {
        catch({
          val clip = clipboard.primaryClip

          log.debug { "onPrimaryClipChanged(clip=$clip)" }
          if (clip == null) return@catch
          val clipDesc = clip.description
          val itemIdx =
            0.rangeUntil(clipDesc.mimeTypeCount).find { idx ->
              listOf(
                  ClipDescription.MIMETYPE_TEXT_PLAIN,
                  ClipDescription.MIMETYPE_TEXT_HTML,
                )
                .contains(clipDesc.getMimeType(idx))
            }

          if (itemIdx == null) {
            log.warn { "No compatible mimetypes in primary clip" }
            return@catch
          }
          val item = clip.getItemAt(itemIdx)
          val text = item.coerceToText(this@GlobalInputService).toString()
          if (text.isBlank()) {
            log.warn { "ignoring empty clipdata \"${text}\"" }
            return@catch
          }
          val clipboardData =
            ClipboardData(
              0,
              0,
              mapOf(
                ClipboardData.Format.Text to
                  ClipboardData.Variant(
                    ClipboardData.Format.Text,
                    text.toByteArray(),
                  )
              ),
            )
          serviceClient.setClipboardData(
            Bundle().apply { putSerializable("clipboardData", clipboardData) }
          )
        }) { err: Throwable ->
          log.error(err) { "unable to update clipboard" }
        }
      }
    }

  /**
   * Called when the service is connected to the system. This is where we set up
   * the cursor and fetch the home packages.
   */
  override fun onServiceConnected() {
    super.onServiceConnected()

    val imeId = deskflowImeInfo
    Log.i(TAG, "imeId=$imeId,imeEnabled=$isDeskflowImeEnabled")
    clipboard.addPrimaryClipChangedListener(this.onClipboardChanged)

    setupMousePointer()

    sendServiceConnectionEvent<GlobalInputService>()

    fetchHomePackages()
    val pkgName = rootInActiveWindow?.packageName
    isHomeScreenActiveFlow.value =
      pkgName == null || knownHomePackages.contains(pkgName)
  }

  /**
   * All accessibility events are received here; we pay little attention to
   * them, but we do check for window state changes to determine if the home
   * screen is active.
   */
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    log.debug { "onAccessibilityEvent: ${event?.eventType}" }
    //
    when (event?.eventType) {
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
        checkIMESetup()
      }
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        val pkgName = rootInActiveWindow?.packageName
        isHomeScreenActiveFlow.value =
          pkgName == null || knownHomePackages.contains(pkgName)
      }
      else -> {
        log.debug { "Ignored event: ${event?.eventType}" }
      }
    }
  }

  override fun onInterrupt() {
    // Required override.
  }

  /**
   * Perform a swipe gesture. If [up] is true, it swipes up, otherwise it swipes
   * down. The swipe starts from the middle of the screen and goes to the top or
   * bottom.
   * > Example: Used for scrolling in lists or returning to the home screen.
   */
  private fun swipe(up: Boolean = false) {
    if (globalInputPending) return

    globalInputPending = true

    val screenSize = getScreenSize()

    val (width, height) =
      Pair(screenSize.px.width.toFloat(), screenSize.px.height.toFloat())
    val middleX = width / 2f

    val (startY, endY) =
      when {
        up && isHomeScreenActive -> {
          Pair(
            pointerY.toFloat(),
            max(pointerY.toFloat() - (height * 0.42f), 0f),
          )
        }

        up -> {
          Pair(height - 5f, height * 0.42f)
        }

        else -> {
          Pair(pointerY.toFloat(), pointerY.toFloat() + (height * 0.5f))
        }
      }

    val path =
      Path().apply {
        moveTo(middleX, startY)
        lineTo(middleX, endY)
      }

    val stroke = StrokeDescription(path, 0, 500)

    val gesture = GestureDescription.Builder().addStroke(stroke).build()

    log.debug {
      "Swipe up=$up,startY=$startY,endY=$endY,screenSize=$screenSize,path=$path"
    }

    dispatchGesture(gesture, gestureResultCallback, globalInputHandler)
  }

  /**
   * Fetch the list of known home packages. This is used to determine if the
   * current screen is the home screen.
   */
  private fun fetchHomePackages() {
    val intent =
      Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
    val resolveInfoList =
      packageManager.queryIntentActivities(
        intent,
        PackageManager.MATCH_DEFAULT_ONLY,
      )
    knownHomePackages.clear()
    for (info in resolveInfoList) {
      knownHomePackages.add(info.activityInfo.packageName)
    }
  }

  /**
   * Click the currently focused node. This is useful for clicking on input
   * fields or buttons that are currently focused.
   * > Example: It's used for the DPAD_CENTER action in the system actions.
   */
  private fun clickFocused() {
    val focusedNode = findFocus(FOCUS_INPUT)
    // val focusedNode =
    // rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    if (focusedNode != null) {
      logNodeHierarchy(focusedNode, 0)
      focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } else {
      log.warn { "No focused node found to click" }
    }
  }

  /// **
  // * Example: continuously move pointer in a circle.
  // * Call this from onServiceConnected or via a button in your overlay.
  // */
  // fun spinPointer(centerX: Int, centerY: Int, radius: Int, steps: Int = 36,
  // intervalMs: Long = 100) {
  //    for (i in 0 until steps) {
  //        uiHandler.postDelayed({
  //            val theta = 2 * Math.PI * i / steps
  //            val x = centerX + (radius * Math.cos(theta)).toInt()
  //            val y = centerY + (radius * Math.sin(theta)).toInt()
  //            movePointer(centerX, centerY, x - centerX, y - centerY)
  //        }, i * intervalMs)
  //    }
  // }

  companion object {
    private const val CHANNEL_ID = "deskflow_service_channel"
    private const val NOTIF_IME_NOT_SETUP_ID = 1

    /**
     * Duration of each live-drag segment; also the chain's pacing interval, since
     * the next segment is dispatched when this one completes (~40 segments/sec).
     * Short enough to track the cursor closely, long enough not to flood the
     * accessibility gesture dispatcher.
     */
    private const val DRAG_STROKE_MS = 24L

    /**
     * Duration of the final release segment. Short, so extrapolating the recent
     * movement over it produces a fast touch-up -- the fling velocity that
     * velocity-sensitive system gestures need to commit on release.
     */
    private const val RELEASE_FLICK_MS = 8L

    /** Pointer push-rate bounds; see [pointerApplyIntervalMs]. */
    private const val POINTER_MIN_HZ = 125
    private const val POINTER_MAX_HZ = 250

    /** How long the wake-on-input lock is held; screen-off timer resumes after. */
    private const val WAKE_ON_INPUT_MS = 3_000L

    private val TAG = GlobalInputService::class.java.simpleName
    private val log =
      KLoggingManager.logger(GlobalInputService::class.java.simpleName)

    private fun logNodeHierarchy(nodeInfo: AccessibilityNodeInfo, depth: Int) {
      val bounds = Rect()
      nodeInfo.getBoundsInScreen(bounds)

      val sb = StringBuilder()
      if (depth > 0) {
        (0..<depth).forEach { _ -> sb.append("  ") }
        sb.append("\u2514 ")
      }
      sb.append(nodeInfo.className)
      sb.append(" (" + nodeInfo.childCount + ")")
      sb.append(" $bounds")
      if (nodeInfo.getText() != null) {
        sb.append(" - \"" + nodeInfo.getText() + "\"")
      }
      log.trace { sb.toString() }

      for (i in 0..<nodeInfo.childCount) {
        val childNode = nodeInfo.getChild(i)
        if (childNode != null) {
          logNodeHierarchy(childNode, depth + 1)
        }
      }
    }

    private fun findSmallestNodeAtPoint(
      sourceNode: AccessibilityNodeInfo,
      x: Int,
      y: Int,
    ): AccessibilityNodeInfo? {
      val bounds = Rect()
      sourceNode.getBoundsInScreen(bounds)

      if (!bounds.contains(x, y)) {
        return null
      }

      for (i in 0..<sourceNode.childCount) {
        val nearestSmaller =
          findSmallestNodeAtPoint(sourceNode.getChild(i), x, y)
        if (nearestSmaller != null) {
          return nearestSmaller
        }
      }
      return sourceNode
    }
  }
}
