package org.tfv.deskflow.services.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tfv.deskflow.BuildConfig
import org.tfv.deskflow.client.util.logging.KLoggingManager
import rikka.shizuku.Shizuku

/**
 * App-side manager for the Shizuku input user service.
 *
 * Tracks whether Shizuku is running, whether we hold its permission, and binds
 * [ShizukuInputUserService] while [setWanted] is true. Shared by
 * GlobalInputService (which injects) and the settings UI (which shows status
 * and requests permission). Shizuku listeners fire on the main thread.
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
    /** Permission granted, user service not bound (feature disabled). */
    Idle,
    Connecting,
    Ready,
  }

  private const val PERMISSION_REQUEST_CODE = 0x5e1f

  private val statusFlow = MutableStateFlow(Status.NotRunning)
  val status: StateFlow<Status> = statusFlow.asStateFlow()

  @Volatile
  var service: IShizukuInputService? = null
    private set

  private var wanted = false
  private var bound = false
  private var initialized = false

  private val userServiceArgs by lazy {
    Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuInputUserService::class.java.name)
      )
      .daemon(false)
      .processNameSuffix("shizuku_input")
      .debuggable(BuildConfig.DEBUG)
      .version(BuildConfig.VERSION_CODE)
  }

  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
        if (binder == null || !binder.pingBinder()) {
          log.warn { "Shizuku user service connected with dead binder" }
          return
        }
        val svc = IShizukuInputService.Stub.asInterface(binder)
        service = svc
        log.info {
          "Shizuku input service connected (uid=${runCatching { svc.uid }.getOrNull()})"
        }
        refresh()
      }

      override fun onServiceDisconnected(name: ComponentName) {
        log.warn { "Shizuku input service disconnected" }
        service = null
        bound = false
        refresh()
      }
    }

  private val binderReceived =
    Shizuku.OnBinderReceivedListener {
      log.info { "Shizuku binder received" }
      refresh()
    }

  private val binderDead =
    Shizuku.OnBinderDeadListener {
      log.warn { "Shizuku binder died" }
      service = null
      bound = false
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
    Shizuku.addBinderReceivedListenerSticky(binderReceived)
    Shizuku.addBinderDeadListener(binderDead)
    Shizuku.addRequestPermissionResultListener(permissionResult)
    refresh()
  }

  /** Bind the user service when possible (true) or unbind it (false). */
  fun setWanted(value: Boolean) {
    init()
    synchronized(this) { wanted = value }
    refresh()
  }

  /** Ask Shizuku to show its permission dialog. */
  fun requestPermission() {
    init()
    if (!Shizuku.pingBinder()) return
    runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
      .onFailure { log.error(it) { "Shizuku permission request failed" } }
  }

  @Synchronized
  fun refresh() {
    val running = Shizuku.pingBinder()
    val granted =
      running &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
          .getOrDefault(false)

    if (wanted && granted && !bound) {
      bound = true
      runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
        .onFailure {
          bound = false
          log.error(it) { "Failed to bind Shizuku user service" }
        }
    } else if ((!wanted || !granted) && bound) {
      if (running) {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
          .onFailure { log.warn(it) { "Failed to unbind Shizuku user service" } }
      }
      bound = false
      service = null
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
        service != null -> Status.Ready
        bound -> Status.Connecting
        else -> Status.Idle
      }
  }
}
