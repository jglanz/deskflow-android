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
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import org.tfv.deskflow.client.models.Size
import org.tfv.deskflow.client.models.SizeF

data class ScreenSize(val px: Size, val dp: SizeF, val scale: Float)

/**
 * Get the screen size for a specific display.
 *
 * @param displayId The ID of the display to get metrics for. If null, uses the context's display.
 * @return ScreenSize containing pixel dimensions, dp dimensions, and scale factor.
 */
fun Context.getScreenSize(displayId: Int? = null): ScreenSize {
    val displayContext = if (displayId != null) {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(displayId) ?: this.display!!
        createDisplayContext(display)
    } else {
        this
    }

    // Use the full display bounds rather than resources.displayMetrics: the
    // latter excludes status/navigation bar insets, and this size is reported
    // to the server as the screen dimensions, so the remote pointer could not
    // reach the status bar or the bottom gesture-navigation area.
    val dm = displayContext.resources.displayMetrics
    val bounds = displayContext
        .getSystemService(WindowManager::class.java)
        .maximumWindowMetrics
        .bounds
    val widthPx = bounds.width()
    val heightPx = bounds.height()
    val widthDp = widthPx / dm.density
    val heightDp = heightPx / dm.density
    return ScreenSize(Size(widthPx, heightPx), SizeF(widthDp, heightDp), widthPx.toFloat() / widthDp)
}
