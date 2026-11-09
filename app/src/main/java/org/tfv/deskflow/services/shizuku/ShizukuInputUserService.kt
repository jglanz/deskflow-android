package org.tfv.deskflow.services.shizuku

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku UserService. Shizuku starts this class with app_process in a
 * separate process running as shell (or root), which has the INJECT_EVENTS
 * permission. Hidden APIs are reachable here via plain reflection because the
 * process is not forked from zygote, so hidden-API enforcement does not apply
 * (the same technique scrcpy's server uses).
 *
 * Keep this class free of app-level dependencies (no DataStore, no app
 * logging): it runs without an Application and with a shell identity.
 */
@Keep
class ShizukuInputUserService() : IShizukuInputService.Stub() {

  /** Shizuku 13+ passes a Context when this constructor exists. */
  @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

  private val inputManager: Any
  private val injectInputEvent: Method
  private val setDisplayId: Method = InputEvent::class.java
    .getMethod("setDisplayId", Int::class.javaPrimitiveType)
  private val setActionButton: Method = MotionEvent::class.java
    .getMethod("setActionButton", Int::class.javaPrimitiveType)

  init {
    val serviceManager = Class.forName("android.os.ServiceManager")
    val binder = serviceManager.getMethod("getService", String::class.java)
      .invoke(null, "input") as IBinder
    inputManager = Class.forName("android.hardware.input.IInputManager\$Stub")
      .getMethod("asInterface", IBinder::class.java)
      .invoke(null, binder)!!
    injectInputEvent = inputManager.javaClass.getMethod(
      "injectInputEvent",
      InputEvent::class.java,
      Int::class.javaPrimitiveType,
    )
    Log.i(TAG, "Started (uid=${Process.myUid()})")
  }

  override fun destroy() {
    Log.i(TAG, "destroy()")
    exitProcess(0)
  }

  override fun getUid(): Int = Process.myUid()

  override fun injectMotionEvent(event: MotionEvent, displayId: Int, actionButton: Int) {
    try {
      setDisplayId.invoke(event, displayId)
      if (actionButton != 0) setActionButton.invoke(event, actionButton)
      val ok = injectInputEvent.invoke(inputManager, event, INJECT_MODE_ASYNC) as Boolean
      if (!ok) Log.w(TAG, "injectInputEvent rejected: $event")
    } catch (err: Throwable) {
      Log.e(TAG, "injectInputEvent failed", err)
    } finally {
      event.recycle()
    }
  }

  private companion object {
    const val TAG = "DeskflowShizukuInput"
    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
    const val INJECT_MODE_ASYNC = 0
  }
}
