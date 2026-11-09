package org.tfv.deskflow.services.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.tfv.deskflow.client.util.logging.KLoggingManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Injects input events through the system `input` service (IInputManager)
 * using Shizuku's shell identity.
 *
 * We call IInputManager from our own process through [ShizukuBinderWrapper],
 * which forwards each transaction via the Shizuku server running as
 * shell/root (so INJECT_EVENTS is satisfied). A Shizuku UserService is not
 * used: some vendor builds (e.g. MediaTek Android 16) crash in
 * LoadedApk.makeApplicationInner while Shizuku starts one, because
 * Application.getProcessName() is null in that process.
 *
 * Hidden framework APIs are unlocked with HiddenApiBypass. Injection runs on
 * a single background thread, so each call is still ordered but never blocks
 * the caller.
 */
object ShizukuInputBridge {
  private val log = KLoggingManager.logger("ShizukuInputBridge")

  enum class Status {
    /** Shizuku is not installed or its server is not started (needs ADB/root after boot). */
    NotRunning,
    /** Shizuku is running but this app has not been granted access. */
    PermissionRequired,
    /** The user denied access with "don't ask again"; must be granted from the Shizuku app. */
    PermissionDenied,
    /** Permission granted, injection not set up (feature disabled). */
    Idle,
    /** Setting up the IInputManager proxy failed; see logs. */
    Error,
    Ready,
  }

  private const val PERMISSION_REQUEST_CODE = 0x5e1f

  /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
  private const val INJECT_MODE_ASYNC = 0

  private val statusFlow = MutableStateFlow(Status.NotRunning)
  val status: StateFlow<Status> = statusFlow.asStateFlow()

  private class Injector(val inputManager: Any, val injectInputEvent: Method)

  @Volatile private var injector: Injector? = null

  val isReady: Boolean
    get() = injector != null

  private val executor: ExecutorService =
    Executors.newSingleThreadExecutor { r -> Thread(r, "ShizukuInput").apply { isDaemon = true } }

  private val setDisplayId: Method? by lazy {
    runCatching {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
      }
      .onFailure { log.error(it) { "InputEvent.setDisplayId unavailable" } }
      .getOrNull()
  }

  private val setActionButton: Method? by lazy {
    runCatching {
        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
      }
      .onFailure { log.error(it) { "MotionEvent.setActionButton unavailable" } }
      .getOrNull()
  }

  private var wanted = false
  private var initialized = false
  private var setupFailed = false

  private val binderReceived =
    Shizuku.OnBinderReceivedListener {
      log.info { "Shizuku binder received" }
      refresh()
    }

  private val binderDead =
    Shizuku.OnBinderDeadListener {
      log.warn { "Shizuku binder died" }
      synchronized(this) {
        injector = null
        setupFailed = false
      }
      refresh()
    }

  private val permissionResult =
    Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
      if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
      log.info {
        "Shizuku permission result: granted=${grantResult == PackageManager.PERMISSION_GRANTED}"
      }
      refresh()
    }

  /** Idempotent. Registers Shizuku listeners. */
  @Synchronized
  fun init() {
    if (initialized) return
    initialized = true
    runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
      .onFailure { log.error(it) { "HiddenApiBypass failed" } }
    Shizuku.addBinderReceivedListenerSticky(binderReceived)
    Shizuku.addBinderDeadListener(binderDead)
    Shizuku.addRequestPermissionResultListener(permissionResult)
    refresh()
  }

  /** Enable (true) or disable (false) injection; sets up the proxy when possible. */
  fun setWanted(value: Boolean) {
    init()
    synchronized(this) {
      wanted = value
      if (value) setupFailed = false
    }
    refresh()
  }

  /** Ask Shizuku to show its permission dialog. */
  fun requestPermission() {
    init()
    if (!Shizuku.pingBinder()) return
    runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
      .onFailure { log.error(it) { "Shizuku permission request failed" } }
  }

  /**
   * Inject [event] asynchronously and recycle it afterwards. Takes ownership
   * of [event]: the caller must not use or recycle it.
   */
  fun inject(event: MotionEvent, displayId: Int, actionButton: Int) {
    val inj = injector
    if (inj == null) {
      event.recycle()
      return
    }
    executor.execute {
      try {
        setDisplayId?.invoke(event, displayId)
        if (actionButton != 0) setActionButton?.invoke(event, actionButton)
        val ok = inj.injectInputEvent.invoke(inj.inputManager, event, INJECT_MODE_ASYNC) as Boolean
        if (!ok) log.warn { "injectInputEvent rejected: $event" }
      } catch (err: Throwable) {
        log.error(err) { "injectInputEvent failed" }
      } finally {
        event.recycle()
      }
    }
  }

  private fun createInjector(): Injector {
    val binder: IBinder =
      ShizukuBinderWrapper(
        SystemServiceHelper.getSystemService("input")
          ?: error("input service not found")
      )
    val inputManager =
      Class.forName("android.hardware.input.IInputManager\$Stub")
        .getMethod("asInterface", IBinder::class.java)
        .invoke(null, binder) ?: error("IInputManager.Stub.asInterface returned null")
    val inject =
      inputManager.javaClass.getMethod(
        "injectInputEvent",
        InputEvent::class.java,
        Int::class.javaPrimitiveType,
      )
    return Injector(inputManager, inject)
  }

  @Synchronized
  fun refresh() {
    val running = Shizuku.pingBinder()
    val granted =
      running &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
          .getOrDefault(false)

    if (wanted && granted && injector == null && !setupFailed) {
      runCatching { createInjector() }
        .onSuccess {
          injector = it
          log.info { "Shizuku input injection ready (server uid=${Shizuku.getUid()})" }
        }
        .onFailure {
          setupFailed = true
          log.error(it) { "Failed to set up Shizuku IInputManager proxy" }
        }
    } else if ((!wanted || !granted) && injector != null) {
      injector = null
      log.info { "Shizuku input injection stopped" }
    }

    statusFlow.value =
      when {
        !running -> Status.NotRunning
        !granted ->
          if (
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
          )
            Status.PermissionDenied
          else Status.PermissionRequired
        injector != null -> Status.Ready
        wanted && setupFailed -> Status.Error
        else -> Status.Idle
      }
  }
}
