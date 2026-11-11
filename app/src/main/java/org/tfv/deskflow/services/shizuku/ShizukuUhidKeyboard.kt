package org.tfv.deskflow.services.shizuku

import android.content.Context
import android.content.pm.PackageManager
import java.io.OutputStream
import java.lang.reflect.Method
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.uhid.UhidMain
import rikka.shizuku.Shizuku

/**
 * Runs the /dev/uhid helper process ([UhidMain]) as the shell user through
 * Shizuku and feeds it keyboard reports.
 *
 * The helper is started with Shizuku.newProcess rather than a Shizuku
 * UserService: Shizuku 13.6's UserService starter calls
 * LoadedApk.makeApplication(), which crashes on MediaTek framework builds
 * (RikkaApps/Shizuku#1198). A bare app_process main() never gets there.
 *
 * newProcess is private in Shizuku API 13 (deprecated in favour of
 * UserService), so it's called reflectively.
 */
object ShizukuUhidKeyboard {
  private val log = KLoggingManager.logger("ShizukuUhidKeyboard")

  /** Name of the input device the helper creates. */
  const val DEVICE_NAME = UhidMain.DEVICE_NAME

  private val newProcess: Method? by lazy {
    runCatching {
        Shizuku::class
          .java
          .getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
          )
          .apply { isAccessible = true }
      }
      .onFailure { log.error(it) { "Shizuku.newProcess unavailable" } }
      .getOrNull()
  }

  private var process: Process? = null
  private var stdin: OutputStream? = null

  /**
   * True from [start] until [stop] or the helper exits. Tracked here rather
   * than via Process.exitValue(): on a Shizuku remote process that's a binder
   * call whose "still running" exception doesn't reliably come back as one.
   */
  val isRunning: Boolean
    @Synchronized get() = process != null

  /**
   * Start the helper if it isn't running. [onExit] runs on a background
   * thread if this helper exits on its own (not after [stop]).
   */
  @Synchronized
  fun start(context: Context, onExit: (Int?) -> Unit = {}): Boolean {
    if (isRunning) return true
    if (!Shizuku.pingBinder()) {
      log.warn { "Shizuku not running" }
      return false
    }
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      log.warn { "Shizuku permission not granted" }
      return false
    }
    val method = newProcess ?: return false
    val apk = context.applicationInfo.sourceDir
    val cmd = "CLASSPATH='$apk' exec /system/bin/app_process / ${UhidMain::class.java.name}"
    return runCatching {
        val p = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        process = p
        stdin = p.outputStream
        Thread(
            {
              runCatching {
                p.errorStream.bufferedReader().forEachLine { log.info { "helper: $it" } }
              }
              val code = runCatching { p.waitFor() }.getOrNull()
              log.info { "UHID helper exited: $code" }
              // Only report unexpected exits of the current helper, not one
              // that stop() ended or a newer start() replaced.
              val current =
                synchronized(this) {
                  (process === p).also {
                    if (it) {
                      process = null
                      stdin = null
                    }
                  }
                }
              if (current) onExit(code)
            },
            "uhid-helper-log",
          )
          .apply { isDaemon = true }
          .start()
        log.info { "UHID helper started" }
        true
      }
      .onFailure { log.error(it) { "Failed to start UHID helper" } }
      .getOrDefault(false)
  }

  /** Send one 8-byte boot keyboard report: [modifiers, 0, key1..key6]. */
  @Synchronized
  fun sendReport(report: ByteArray): Boolean {
    require(report.size == UhidMain.KEYBOARD_REPORT_SIZE)
    val out = stdin ?: return false
    return runCatching {
        out.write(UhidMain.MSG_KEYBOARD_REPORT)
        out.write(report.size)
        out.write(report)
        out.flush()
        true
      }
      .onFailure {
        log.error(it) { "UHID helper write failed" }
        stop()
      }
      .getOrDefault(false)
  }

  /** Closing stdin makes the helper destroy the device and exit. */
  @Synchronized
  fun stop() {
    runCatching { stdin?.close() }
    runCatching { process?.destroy() }
    stdin = null
    process = null
  }
}
