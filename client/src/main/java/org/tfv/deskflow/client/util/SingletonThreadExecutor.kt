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

package org.tfv.deskflow.client.util

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

class SingletonThreadExecutor(val threadName: String) : AbstractDisposable() {
  private val threadLock = Any()

  // Tracks the current worker so [isExecutorThread] stays accurate. The factory
  // MUST return a fresh Thread on every call: a single-thread executor asks the
  // factory for a replacement if the worker dies, and reusing an already-started
  // (or dead) Thread throws IllegalThreadStateException and permanently wedges
  // the executor.
  private var thread: Thread? = null
  private val threadFactory = ThreadFactory { r ->
    synchronized(threadLock) {
      val t = Thread(r, threadName)
      thread = t
      return@ThreadFactory t
    }
  }

  val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(1, threadFactory)

  val isExecutorThread: Boolean
    get() = Thread.currentThread() == thread
  
  @Suppress("DiscouragedApi")
  fun scheduleAtFixedRate(runnable: Runnable, period: Long, initialDelay: Long = 0, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
    executor.scheduleAtFixedRate(runnable, initialDelay, period, timeUnit)
  }
  
  fun scheduleWithFixedDelay(runnable: Runnable, delay: Long, initialDelay: Long = 0, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
    executor.scheduleWithFixedDelay(runnable, initialDelay, delay, timeUnit)
  }
  
  fun schedule(runnable: Runnable, delay: Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
    executor.schedule(runnable, delay, timeUnit)
  }
  
  fun <T> submit(runnable: () -> T): Future<T>? {
    synchronized(threadLock) {
      if (executor.isShutdown) {
        return null
      }
    }
    return executor.submit<T>(runnable)
  }

  fun <T> submit(callable: Callable<T>): Future<T>? {
    synchronized(threadLock) {
      if (executor.isShutdown) {
        return null
      }
    }
    return executor.submit(callable)
  }

  fun shutdown() {
    synchronized(threadLock) {
      if (executor.isShutdown) {
        return
      }
      executor.shutdownNow()
    }
  }

  override fun onDispose() {
    shutdown()
  }

}