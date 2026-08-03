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

package org.tfv.deskflow.client.events

import java.io.Serializable

data class MouseEvent(val type: Type, val id: UInt, val x: Int, val y: Int) : ClientEvent(), Serializable {
    enum class Type {
        Up,
        Down,
        Move,
        MoveRelative,
        Wheel
    }

    companion object {
        fun down(id: UInt) = MouseEvent(Type.Down, id,0,0)
        fun up(id: UInt) = MouseEvent(Type.Up, id,0,0)
        fun move(x: Int, y:Int) = MouseEvent(Type.Move,0u,x,y)
        fun moveRelative(x: Int, y:Int) = MouseEvent(Type.MoveRelative,0u,x,y)
        fun wheel(x: Int, y:Int) = MouseEvent(Type.Wheel,0u,x,y)
    }
}

/**
 * Deskflow mouse button ids as carried on the wire (Synergy/Barrier/Deskflow
 * convention): 1=left, 2=right, 3=middle, 4=X1/back, 5=X2/forward.
 */
object MouseButton {
    val LEFT: UInt = 1u
    val RIGHT: UInt = 2u
    val MIDDLE: UInt = 3u
    val X1_BACK: UInt = 4u
    val X2_FORWARD: UInt = 5u
}