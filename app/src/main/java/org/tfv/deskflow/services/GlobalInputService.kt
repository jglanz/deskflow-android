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

import android.media.AudioManager
import android.provider.Settings
import android.widget.Toast
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.tfv.deskflow.R
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.util.Keyboard
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.components.GlobalKeyboardManager
import org.tfv.deskflow.ext.canDrawOverlays
import org.tfv.deskflow.ext.getScreenSize
import org.tfv.deskflow.ext.sendServiceConnectionEvent

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@SuppressLint("ServiceCast", "NewApi")
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
    if (
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
    imeInfo: InputMethodInfo? = imeManager.currentInputMethodInfo
  ): Boolean {
    return imeInfo?.let { current -> deskflowImeInfo?.id == current.id }
      ?: false
  }

  @SuppressLint("NewApi")
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

    val imeInfo = imeManager.currentInputMethodInfo
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

    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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
    if (handleSystemKey(event)) return
    keyboardManager.process(event)
  }

  /**
   * Volume, media and brightness keys from the server control this device.
   * Returns true when the event was one of those keys.
   */
  private fun handleSystemKey(event: KeyboardEvent): Boolean {
    val key = Keyboard.findSpecialKey(event.id.toInt()) ?: return false
    val mediaKeyCode =
      when (key) {
        Keyboard.SpecialKey.AudioPlay -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        Keyboard.SpecialKey.AudioNext -> KeyEvent.KEYCODE_MEDIA_NEXT
        Keyboard.SpecialKey.AudioPrev -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        Keyboard.SpecialKey.AudioStop -> KeyEvent.KEYCODE_MEDIA_STOP
        else -> null
      }
    if (mediaKeyCode != null) {
      // Forward both down and up so the media session sees a full press;
      // auto-repeat would toggle play/pause over and over, so drop it.
      if (event.type == KeyboardEvent.Type.Repeat) return true
      val action =
        if (event.type == KeyboardEvent.Type.Up) KeyEvent.ACTION_UP
        else KeyEvent.ACTION_DOWN
      audioManager.dispatchMediaKeyEvent(KeyEvent(action, mediaKeyCode))
      return true
    }

    val audioAdjust =
      when (key) {
        Keyboard.SpecialKey.AudioUp -> AudioManager.ADJUST_RAISE
        Keyboard.SpecialKey.AudioDown -> AudioManager.ADJUST_LOWER
        Keyboard.SpecialKey.AudioMute -> AudioManager.ADJUST_TOGGLE_MUTE
        Keyboard.SpecialKey.BrightnessUp,
        Keyboard.SpecialKey.BrightnessDown -> null
        else -> return false
      }
    // Act on key down and auto-repeat; the matching up event is swallowed.
    if (event.type == KeyboardEvent.Type.Up) return true

    if (audioAdjust != null) {
      audioManager.adjustSuggestedStreamVolume(
        audioAdjust,
        AudioManager.USE_DEFAULT_STREAM_TYPE,
        AudioManager.FLAG_SHOW_UI,
      )
    } else {
      adjustBrightness(up = key == Keyboard.SpecialKey.BrightnessUp)
    }
    return true
  }

  private fun adjustBrightness(up: Boolean) {
    if (!Settings.System.canWrite(this)) {
      log.warn { "WRITE_SETTINGS not granted, cannot change brightness" }
      Toast.makeText(
          this,
          "Allow \"Modify system settings\" for Deskflow to change brightness",
          Toast.LENGTH_LONG,
        )
        .show()
      return
    }

    val resolver = contentResolver
    Settings.System.putInt(
      resolver,
      Settings.System.SCREEN_BRIGHTNESS_MODE,
      Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    )
    val current =
      Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    // Step on a square-root scale so steps feel even to the eye.
    val steps = 16
    val level = Math.round(sqrt(current / 255.0) * steps).toInt()
    val newLevel = (level + if (up) 1 else -1).coerceIn(1, steps)
    val value =
      (newLevel.toDouble() / steps).pow(2).times(255).roundToInt().coerceIn(1, 255)
    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
    log.debug { "Brightness $current -> $value" }
    Toast.makeText(this, "Brightness ${newLevel * 100 / steps}%", Toast.LENGTH_SHORT)
      .show()
  }

  private val audioManager by lazy {
    getSystemService(AUDIO_SERVICE) as AudioManager
  }

  /**
   * Called when the service is destroyed. This is where we clean up resources
   * and remove the mouse pointer view.
   */
  override fun onDestroy() {
    serviceScope.cancel()
    serviceClient.unbind()
    windowManager.removeView(mousePointerView)
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
      findSmallestNodeAtPoint(
        nodeInfo,
        mousePointerLayout.x,
        mousePointerLayout.y,
      )
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
    x: Float = mousePointerLayout.x.toFloat(),
    y: Float = mousePointerLayout.y.toFloat(),
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

    val screenSize = getScreenSize()
    log.debug {
      "Cursor move to [${x}, ${y}] with size(${screenSize.px.width},${screenSize.px.height})"
    }
    mousePointerLayout.x = x
    mousePointerLayout.y = y

    windowManager.updateViewLayout(mousePointerView, mousePointerLayout)
  }

  /**
   * Move the cursor to the specified coordinates. This is used to move the
   * mouse pointer to a specific location on the screen.
   * > Example: Used for mouse movement in the global input service.
   */
  private fun onMouseEvent(event: MouseEvent) {
    when (event.type) {
      MouseEvent.Type.Move -> {
        moveMousePointer(event.x, event.y)
      }

      MouseEvent.Type.MoveRelative -> {
        moveMousePointer(
          mousePointerLayout.x + event.x,
          mousePointerLayout.y + event.y,
        )
      }

      MouseEvent.Type.Down -> {
        log.debug { "Down [$event]" }
      }

      MouseEvent.Type.Up -> {
        log.debug { "Up [$event]" }
        clickGesture()
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
            mousePointerLayout.y.toFloat(),
            max(mousePointerLayout.y.toFloat() - (height * 0.42f), 0f),
          )
        }

        up -> {
          Pair(height - 5f, height * 0.42f)
        }

        else -> {
          Pair(
            mousePointerLayout.y.toFloat(),
            mousePointerLayout.y.toFloat() + (height * 0.5f),
          )
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
