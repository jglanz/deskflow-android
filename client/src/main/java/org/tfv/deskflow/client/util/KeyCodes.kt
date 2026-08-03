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

object Keyboard {
  enum class SpecialKey(val code: Int, val imeText: String? = null) {
    // TODO: Convert to Special enum
    BackSpace(0xEF08), /* back space, back char */
    Tab(0xEF09, "\t"),
    Linefeed(0xEF0A), /* Linefeed, LF */
    Clear(0xEF0B),
    Return(0xEF0D, "\n"), /* Return, enter */
    Pause(0xEF13),  /* Pause, hold */
    ScrollLock(0xEF14),
    SysReq(0xEF15),
    Escape(0xEF1B),
    Henkan(0xEF23),           /* Start/Stop Conversion */
    Kana(0xEF26),             /* Kana */
    HiraganaKatakana(0xEF27), /* Hiragana/Katakana toggle */
    Zenkaku(0xEF2A),          /* Zenkaku/Hankaku */
    Kanzi(0xEF2A),            /* Kanzi */
    Hangul(0xEF31),           /* Hangul */
    Hanja(0xEF34),            /* Hanja */
    Delete(0xEFFF),           /* Delete, rubout */

    // cursor control
    Home(0xEF50),
    Left(0xEF51),  /* Move left, left arrow */
    Up(0xEF52),    /* Move up, up arrow */
    Right(0xEF53), /* Move right, right arrow */
    Down(0xEF54),  /* Move down, down arrow */
    PageUp(0xEF55),
    PageDown(0xEF56),
    End(0xEF57),   /* EOL */
    Begin(0xEF58), /* BOL */

    // Audio / media keys. These are the Deskflow wire KeyIDs (KeyTypes.h
    // kKeyAudio*); the server translates platform-native volume keys (X11
    // XF86Audio*, Windows VK_VOLUME_*, macOS) into these 16-bit values before
    // transmission, so they fit the %2i key field verbatim.
    VolumeMute(0xE0AD),
    VolumeDown(0xE0AE),
    VolumeUp(0xE0AF);

    val label: String = name
  }

  fun findSpecialKey(keyCodeLabel: String): SpecialKey? {
    return SpecialKey.entries.firstOrNull { it.name.lowercase() == keyCodeLabel.lowercase() }
  }

  fun findSpecialKey(keyCode: Int): SpecialKey? {
    return SpecialKey.entries.firstOrNull { it.code == keyCode }
  }

  enum class ModifierKey(val code: Int, val mask: ModifierKeyMask) {
    Shift(0xEFE1, ModifierKeyMask.Shift),   /* Left shift */
    ShiftRight(0xEFE2, ModifierKeyMask.Shift),   /* Right shift */
    Control(0xEFE3, ModifierKeyMask.Control), /* Left control */
    ControlRight(0xEFE4, ModifierKeyMask.Control), /* Right control */
    CapsLock(0xEFE5, ModifierKeyMask.CapsLock),  /* Caps lock */
    ShiftLock(0xEFE6, ModifierKeyMask.None), /* Shift lock */
    Meta(0xEFE7, ModifierKeyMask.Meta),    /* Left meta */
    MetaRight(0xEFE8, ModifierKeyMask.Meta),    /* Right meta */
    Alt(0xEFE9, ModifierKeyMask.Alt),     /* Left alt */
    AltRight(0xEFEA, ModifierKeyMask.Alt),     /* Right alt */
    Super(0xEFEB, ModifierKeyMask.Super),   /* Left super */
    SuperRight(0xEFEC, ModifierKeyMask.Super),   /* Right super */
    Hyper(0xEFED, ModifierKeyMask.None),   /* Left hyper */
    HyperRight(0xEFEE, ModifierKeyMask.None),   /* Right hyper */
  }

  fun findModifierKey(keyCodeLabel: String): ModifierKey? {
    return ModifierKey.entries.firstOrNull { it.name.lowercase() == keyCodeLabel.lowercase() }
  }

  fun findModifierKey(keyCode: Int): ModifierKey? {
    return ModifierKey.entries.firstOrNull { it.code == keyCode }
  }

  fun isModifierKey(keyCodeLabel: String):Boolean {
    return findModifierKey(keyCodeLabel) != null
  }

  fun isModifierKey(keyCode: Int):Boolean {
    return findModifierKey(keyCode) != null
  }

    enum class ModifierKeyMask(val bit: Int, val label: String) {
    None(16, "None"),
    Shift(0, "Shift"),
    Control(1, "Control"),
    Alt(2, "Alt"),
    Meta(3, "Meta"),
    Super(4, "Super"),
    AltGr(5, "AltGr"),
    Level5Lock(6, "Level5Lock"),
    CapsLock(12, "CapsLock"),
    NumLock(13, "NumLock"),
    ScrollLock(14, "ScrollLock"),;
  }

}