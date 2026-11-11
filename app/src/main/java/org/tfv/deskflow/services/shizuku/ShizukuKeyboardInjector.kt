package org.tfv.deskflow.services.shizuku

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.uhid.UhidMain

/**
 * Types Deskflow keyboard input on a virtual hardware keyboard (/dev/uhid via
 * [ShizukuUhidKeyboard]) instead of through the Deskflow IME.
 *
 * Android treats the device as a real physical keyboard: the layout chosen in
 * Settings → Physical keyboard applies, apps get genuine key events (Escape,
 * Ctrl shortcuts, terminal keys), and the on-screen keyboard stays hidden.
 *
 * The keyboard exists while the feature is enabled, Shizuku is usable and the
 * client is connected to a server.
 *
 * Threading: [handle], [releaseAll] and the lifecycle setters run on the main
 * thread (GlobalInputService's handler). [wouldInject] is safe from any thread
 * and lets VirtualKeyboardService skip keys this path types.
 */
object ShizukuKeyboardInjector {
  private val log = KLoggingManager.logger("ShizukuKeyboardInjector")

  enum class Status {
    Disabled,
    /** Shizuku isn't running or permission isn't granted; see ShizukuInputBridge.status. */
    WaitingForShizuku,
    /** Enabled, but not connected to a server, so no keyboard is attached. */
    WaitingForConnection,
    Starting,
    Ready,
    Error,
  }

  private const val MAX_RESTARTS = 3
  private const val RESTART_DELAY_MS = 2000L
  private const val MAX_KEYS = 6
  private const val ATTACH_CHECK_DELAY_MS = 1500L

  private val statusFlow = MutableStateFlow(Status.Disabled)
  val status: StateFlow<Status> = statusFlow.asStateFlow()

  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
  private var appContext: Context? = null
  private var inputManager: InputManager? = null

  private var wanted = false
  private var connected = false
  private var restarts = 0

  /** Char → keystroke for the attached device's current layout; null while no device. */
  @Volatile private var charMap: Map<Int, UhidKeyMap.Stroke>? = null
  private var deviceId = -1

  /** Modifier bits from modifier key events the server sent. */
  private var heldModifiers = 0

  /** Pressed non-modifier keys, oldest first. */
  private val pressed = LinkedHashMap<Long, PressedKey>()

  /** [modifiers] overrides the held modifiers while this is the newest key (characters). */
  private data class PressedKey(val usage: Int, val modifiers: Int?)

  /** True while the virtual keyboard is attached and typing goes through it. */
  val isActive: Boolean
    get() = charMap != null

  private val deviceListener =
    object : InputManager.InputDeviceListener {
      override fun onInputDeviceAdded(id: Int) = checkDevice(id)

      // Also fires when the user picks a different layout for the device.
      override fun onInputDeviceChanged(id: Int) = checkDevice(id)

      override fun onInputDeviceRemoved(id: Int) {
        if (id != deviceId) return
        log.info { "Virtual keyboard removed" }
        detach()
        update()
      }
    }

  /** Idempotent. */
  fun init(context: Context) {
    if (appContext != null) return
    appContext = context.applicationContext
    inputManager =
      (context.getSystemService(Context.INPUT_SERVICE) as InputManager).also {
        it.registerInputDeviceListener(deviceListener, main)
      }
    ShizukuInputBridge.init()
    // Shizuku starting or permission being granted may unblock us.
    scope.launch { ShizukuInputBridge.status.collect { update() } }
  }

  fun setWanted(value: Boolean) {
    if (wanted == value) return
    wanted = value
    restarts = 0
    log.info { "Shizuku keyboard ${if (value) "enabled" else "disabled"}" }
    update()
  }

  fun setConnected(value: Boolean) {
    if (connected == value) return
    connected = value
    restarts = 0
    update()
  }

  /** Stop everything, e.g. when the owning service is destroyed. */
  fun shutdown() {
    wanted = false
    update()
  }

  /**
   * Whether [handle] types this event on the virtual keyboard. Pure function of
   * the event and the current layout, so other services can use it to skip
   * keys without coordinating with the main thread.
   */
  fun wouldInject(event: KeyboardEvent): Boolean {
    val map = charMap ?: return false
    val id = event.id.toInt()
    return id in UhidKeyMap.MODIFIER_KEYS ||
      id in UhidKeyMap.IGNORED_KEYS ||
      id in UhidKeyMap.SPECIAL_KEYS ||
      UhidKeyMap.KEYPAD_CHARS[id]?.let { map.containsKey(it.code) } == true ||
      map.containsKey(id)
  }

  /** Type [event] on the virtual keyboard. Returns false if this path can't type it. */
  fun handle(event: KeyboardEvent): Boolean {
    if (!wouldInject(event)) return false
    val map = charMap ?: return false
    val id = event.id.toInt()

    // Android auto-repeats held keys itself.
    if (event.type == KeyboardEvent.Type.Repeat) return true
    if (id in UhidKeyMap.IGNORED_KEYS) return true

    val isDown = event.type == KeyboardEvent.Type.Down
    UhidKeyMap.MODIFIER_KEYS[id]?.let { bit ->
      heldModifiers = if (isDown) heldModifiers or bit else heldModifiers and bit.inv()
      sendReport()
      return true
    }

    val key = keyOf(event)
    if (!isDown) {
      // Up events normally carry the same button as their Down; fall back to the id.
      if (pressed.remove(key) == null) pressed.remove(id.toLong() or ID_FLAG)
      sendReport()
      return true
    }
    if (key in pressed) return true
    if (pressed.size >= MAX_KEYS) {
      log.warn { "Too many keys held, ignoring $event" }
      return true
    }

    val special = UhidKeyMap.SPECIAL_KEYS[id]
    pressed[key] =
      if (special != null) {
        // The event's mask also covers modifiers pressed before the cursor
        // entered this screen, which we never saw as key events.
        PressedKey(
          special,
          heldModifiers or UhidKeyMap.modifiersFromMask(event.mask, includeLayoutModifiers = true),
        )
      } else {
        val ch = UhidKeyMap.KEYPAD_CHARS[id]?.code ?: id
        val stroke = map[ch] ?: return false
        // The layout decides Shift/AltGr for a character; keep the server's
        // Ctrl/Alt/Meta so shortcuts like Ctrl+C still work.
        val layoutBits = UhidKeyMap.MOD_LSHIFT or UhidKeyMap.MOD_RSHIFT or UhidKeyMap.MOD_RALT
        val mods =
          (heldModifiers and layoutBits.inv()) or
            UhidKeyMap.modifiersFromMask(event.mask, includeLayoutModifiers = false) or
            stroke.modifiers
        PressedKey(stroke.usage, mods)
      }
    sendReport()
    return true
  }

  /** Release every key, e.g. when the cursor leaves this screen. */
  fun releaseAll() {
    if (heldModifiers == 0 && pressed.isEmpty()) return
    log.debug { "Releasing held keys" }
    heldModifiers = 0
    pressed.clear()
    sendReport()
  }

  private const val ID_FLAG = 1L shl 40

  /** Pair Down/Up by the server's key button when present, else by key id. */
  private fun keyOf(event: KeyboardEvent): Long =
    if (event.button != 0u) event.button.toLong() else event.id.toLong() or ID_FLAG

  private fun sendReport() {
    if (charMap == null) return
    val report = ByteArray(UhidMain.KEYBOARD_REPORT_SIZE)
    val newest = pressed.values.lastOrNull()
    report[0] = (newest?.modifiers ?: heldModifiers).toByte()
    pressed.values.forEachIndexed { i, key -> report[2 + i] = key.usage.toByte() }
    ShizukuUhidKeyboard.sendReport(report)
  }

  private fun checkDevice(id: Int) {
    // Identified by the helper's VID/PID (or name), not by whether the
    // helper is running, so a missed or early callback can't lose it.
    val device = InputDevice.getDevice(id)
    if (device == null) {
      log.debug { "Input device $id not visible" }
      return
    }
    val ours =
      device.vendorId == UhidMain.VENDOR_ID && device.productId == UhidMain.PRODUCT_ID ||
        device.name == ShizukuUhidKeyboard.DEVICE_NAME
    if (!ours) {
      log.debug { "Input device $id is '${device.name}'" }
      return
    }
    if (!wanted || !connected) {
      log.info { "Ignoring virtual keyboard (device=$id) while not in use" }
      return
    }
    runCatching { UhidKeyMap.buildCharMap(device) }
      .onSuccess { map ->
        val first = deviceId != id
        deviceId = id
        charMap = map
        restarts = 0
        statusFlow.value = Status.Ready
        log.info {
          "Virtual keyboard ${if (first) "attached" else "updated"} " +
            "(device=$id, ${map.size} characters)"
        }
        if (first) releaseAll()
      }
      .onFailure { log.error(it) { "Failed to read layout of virtual keyboard" } }
  }

  private fun detach() {
    charMap = null
    deviceId = -1
    heldModifiers = 0
    pressed.clear()
  }

  private fun update() {
    val ctx = appContext ?: return
    if (!wanted || !connected) {
      if (ShizukuUhidKeyboard.isRunning) {
        log.info { "Removing virtual keyboard" }
        releaseAll()
        ShizukuUhidKeyboard.stop()
      }
      detach()
      statusFlow.value = if (!wanted) Status.Disabled else Status.WaitingForConnection
      return
    }
    when (ShizukuInputBridge.status.value) {
      ShizukuInputBridge.Status.NotRunning,
      ShizukuInputBridge.Status.PermissionRequired,
      ShizukuInputBridge.Status.PermissionDenied -> {
        statusFlow.value = Status.WaitingForShizuku
        return
      }
      else -> Unit
    }
    if (ShizukuUhidKeyboard.isRunning) {
      if (charMap == null) findExistingDevice()
      return
    }
    statusFlow.value = Status.Starting
    val started = ShizukuUhidKeyboard.start(ctx) { code -> main.post { onHelperExit(code) } }
    if (!started) {
      statusFlow.value = Status.Error
      return
    }
    // Backstop in case the device-added callback is missed.
    main.postDelayed(
      { if (charMap == null && ShizukuUhidKeyboard.isRunning) findExistingDevice() },
      ATTACH_CHECK_DELAY_MS,
    )
  }

  /** The device may have been added before we started listening. */
  private fun findExistingDevice() {
    val im = inputManager ?: return
    val ids = im.inputDeviceIds
    log.debug {
      "Looking for virtual keyboard among: " +
        ids.joinToString {
          val d = InputDevice.getDevice(it)
          "$it='${d?.name}' (%04x:%04x)".format(d?.vendorId ?: 0, d?.productId ?: 0)
        }
    }
    for (id in ids) checkDevice(id)
  }

  private fun onHelperExit(code: Int?) {
    detach()
    if (!wanted || !connected) return
    if (restarts < MAX_RESTARTS) {
      restarts++
      log.warn { "UHID helper exited ($code), restarting (attempt $restarts)" }
      statusFlow.value = Status.Starting
      main.postDelayed({ update() }, RESTART_DELAY_MS)
    } else {
      log.error { "UHID helper keeps exiting ($code), giving up" }
      statusFlow.value = Status.Error
    }
  }
}
