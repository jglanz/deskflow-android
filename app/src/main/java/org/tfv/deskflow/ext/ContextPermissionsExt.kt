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

@file:OptIn(ExperimentalAtomicApi::class)
@file:Suppress("unused")

package org.tfv.deskflow.ext

import android.accessibilityservice.AccessibilityService
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.text.TextUtils
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import arrow.core.raise.catch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.logging.KLoggingManager

private val log = KLoggingManager.logger("ContextPermissions")

/** Check if draw overlays is enabled for the app */
fun Context.canDrawOverlays(): Boolean {
  return Settings.canDrawOverlays(this)
}

fun Context.requestOverlayPermission() {
  if (!canDrawOverlays()) {
    val intent =
      Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${packageName}".toUri(),
      )
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    startActivity(intent)
  }
}

fun Context.isAccessibilityServiceEnabled(
  serviceClass: Class<out AccessibilityService>
): Boolean {
  try {
    val expectedComponentName = ComponentName(this, serviceClass)
    val enabledServicesSetting =
      Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
      val componentName =
        ComponentName.unflattenFromString(colonSplitter.next())
      if (componentName != null && componentName == expectedComponentName) {
        return true
      }
    }
  } catch (err: Exception) {
    log.warn(err) { "Failed to check if accessibility service is enabled" }
  }
  return false
}

fun Context.requestAccessibilityEnabled() {
  val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
  intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
  startActivity(intent)
}

fun Context.observeAccessibilityStatus(
  serviceClass: Class<out AccessibilityService>,
  scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
  intervalMillis: Long = 1000,
  onChange: (Boolean) -> Unit,
): () -> Unit {
  val enabled = AtomicBoolean(true)
  val job =
    scope.launch {
      var lastStatus = false
      while (this.isActive && enabled.load()) {
        val currentStatus = isAccessibilityServiceEnabled(serviceClass)
        if (currentStatus != lastStatus) {
          lastStatus = currentStatus
          onChange(currentStatus)
        }
        delay(intervalMillis) // check every second
      }
    }

  return {
    enabled.store(false)
    if (job.isActive) {
      job.cancel()
    }
  }
}

val Context.SERVICE_CONNECTED_ACTION: String
  get() = "${packageName}.SERVICE_CONNECTED"

fun <T : Service> Context.registerForServiceConnectionEvents(
  serviceClazz: KClass<T>,
  onServiceConnected: (serviceClazz: KClass<T>) -> Unit,
  once: Boolean = false,
): AutoCloseable {
  return object : AbstractDisposable() {
    private val filter = IntentFilter(SERVICE_CONNECTED_ACTION)
    private val serviceClazzName = serviceClazz.java.name
    private val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (isDisposed) return

          val intentServiceClazzName = intent.getStringExtra("serviceClazz")
          if (intentServiceClazzName != serviceClazzName) {
            log.debug {
              "$intentServiceClazzName is not $serviceClazzName, not firing"
            }
            return
          }
          onServiceConnected(serviceClazz)
          if (once) {
            log.debug {
              "Removing BroadcastReceiver for ${serviceClazz.java.simpleName}"
            }
            disposeReceiver()
          }
        }
      }

    init {
      registerReceiver(receiver, filter, RECEIVER_EXPORTED)
    }

    private fun disposeReceiver() {
      dispose()
    }

    override fun onDispose() {
      unregisterReceiver(receiver)
    }
  }
}

inline fun <reified T : Service> Context.sendServiceConnectionEvent() {
  val log = KLoggingManager.logger<T>()

  val serviceClazz = T::class
  log.debug {
    "sendServiceConnectionEvent($SERVICE_CONNECTED_ACTION) ${serviceClazz.java.simpleName}"
  }
  val intent =
    Intent(SERVICE_CONNECTED_ACTION).apply {
      putExtra("serviceClazz", serviceClazz.java.name)
    }
  sendBroadcast(intent)
}

const val DESKFLOW_PACKAGE = "org.tfv.deskflow"

fun Context.launchInputMethodServiceSettings() {
  val intent =
    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  startActivity(intent)
}

fun Context.allInputMethodServices(): List<InputMethodInfo> {
  val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

  return imm.inputMethodList
}

fun Context.enabledInputMethodServices(): List<InputMethodInfo> {
  val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

  return imm.enabledInputMethodList
}

/** @param imePackage the package name of your IME, e.g. "com.example.myime" */
fun Context.isInputMethodServiceEnabled(
  imePackage: String = DESKFLOW_PACKAGE
): Boolean {
  return catch({
    enabledInputMethodServices().any { it.packageName == imePackage }
  }) { err: Exception ->
    log.warn(err) { "Failed to check if IME is enabled" }
    false
  }
}
