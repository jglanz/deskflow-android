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

package org.tfv.deskflow.ext

import android.content.Context
import android.view.WindowManager
import org.tfv.deskflow.client.models.Size
import org.tfv.deskflow.client.models.SizeF

data class ScreenSize(val px: Size, val dp: SizeF, val scale: Float)

/**
 * The full display size, in pixels and dp.
 *
 * Deliberately NOT `resources.displayMetrics`: that reports the area available
 * to the app, which excludes the status and navigation bar insets. This value is
 * what gets reported to the Deskflow server as the screen dimensions, so using
 * the inset-reduced size made the remote pointer's travel stop short of the real
 * screen edges -- on a 2000x1200 landscape tablet the bottom 59px (36 status +
 * 23 navigation) were unreachable, which includes the gesture-navigation handle.
 *
 * `currentWindowMetrics.bounds` is the full display area, insets included.
 */
fun Context.getScreenSize(): ScreenSize {
    val dm = resources.displayMetrics
    val bounds =
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
    val widthPx = bounds.width()
    val heightPx = bounds.height()
    val widthDp = widthPx / dm.density
    val heightDp = heightPx / dm.density
    return ScreenSize(Size(widthPx, heightPx), SizeF(widthDp, heightDp), widthPx.toFloat() / widthDp)
}