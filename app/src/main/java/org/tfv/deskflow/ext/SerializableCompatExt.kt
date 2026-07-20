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

@file:Suppress("unused")

package org.tfv.deskflow.ext

import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.io.Serializable

/*
 * Type-safe Serializable accessors that work below API 33.
 *
 * The `getSerializable(String, Class)` / `getSerializableExtra(String, Class)`
 * overloads were added in API 33. Calling them on API 31/32 does not fail at
 * compile time -- the app compiles against a newer SDK -- it throws
 * NoSuchMethodError at runtime. Since these sit on the mouse, keyboard and
 * clipboard paths, that is an immediate crash on every event the client
 * receives.
 *
 * Below 33 we fall back to the untyped, deprecated single-argument form and
 * cast. `Class.cast` is used rather than an unchecked Kotlin cast so a payload
 * of the wrong type surfaces as a ClassCastException here instead of further
 * down the call chain.
 */

fun <T : Serializable> Bundle.getSerializableCompat(
  key: String,
  clazz: Class<T>,
): T? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getSerializable(key, clazz)
  } else {
    @Suppress("DEPRECATION") getSerializable(key)?.let(clazz::cast)
  }

fun <T : Serializable> Intent.getSerializableExtraCompat(
  key: String,
  clazz: Class<T>,
): T? =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getSerializableExtra(key, clazz)
  } else {
    @Suppress("DEPRECATION") getSerializableExtra(key)?.let(clazz::cast)
  }
