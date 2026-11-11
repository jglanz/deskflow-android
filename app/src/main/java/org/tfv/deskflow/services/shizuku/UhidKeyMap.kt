package org.tfv.deskflow.services.shizuku

import android.os.Build
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Maps Deskflow key IDs to USB HID keyboard usages (page 0x07) for the
 * virtual UHID keyboard.
 *
 * Deskflow sends the *character* a key produced on the server (or an X11-style
 * keysym for non-character keys), not a portable physical key code. So
 * characters are resolved against the layout Android currently applies to our
 * virtual keyboard: for every physical key position we ask the device which
 * keycode it produces ([InputDevice.getKeyCodeForKeyLocation], which honours
 * the layout's `map key` overrides), then which characters that keycode gives
 * with Shift / AltGr ([KeyCharacterMap.get]). The result is a char → keystroke
 * table that stays correct for whatever layout the user picks in
 * Settings → Physical keyboard.
 *
 * Below API 33 there is no way to query a layout's `map key` overrides, so each
 * position is assumed to produce its Generic.kl (US) keycode. Layouts that only
 * change characters work; layouts that remap keys (e.g. QWERTZ's Y/Z swap) will
 * type the wrong character on those keys.
 */
object UhidKeyMap {
  /** HID modifier byte bits. */
  const val MOD_LCTRL = 0x01
  const val MOD_LSHIFT = 0x02
  const val MOD_LALT = 0x04
  const val MOD_LGUI = 0x08
  const val MOD_RCTRL = 0x10
  const val MOD_RSHIFT = 0x20
  const val MOD_RALT = 0x40
  const val MOD_RGUI = 0x80

  /** A key position plus the layout modifiers (Shift / AltGr) needed for a character. */
  data class Stroke(val usage: Int, val shift: Boolean, val altGr: Boolean) {
    val modifiers: Int
      get() = (if (shift) MOD_LSHIFT else 0) or (if (altGr) MOD_RALT else 0)
  }

  /** Deskflow modifier key IDs → HID modifier bit. Lock keys are deliberately absent. */
  val MODIFIER_KEYS: Map<Int, Int> =
    mapOf(
      0xEFE1 to MOD_LSHIFT, // Shift_L
      0xEFE2 to MOD_RSHIFT, // Shift_R
      0xEFE3 to MOD_LCTRL, // Control_L
      0xEFE4 to MOD_RCTRL, // Control_R
      0xEFE7 to MOD_LGUI, // Meta_L
      0xEFE8 to MOD_RGUI, // Meta_R
      0xEFE9 to MOD_LALT, // Alt_L
      0xEFEA to MOD_RALT, // Alt_R (AltGr on ISO layouts)
      0xEFEB to MOD_LGUI, // Super_L
      0xEFEC to MOD_RGUI, // Super_R
      0xEE03 to MOD_RALT, // ISO_Level3_Shift (AltGr)
      0xEF7E to MOD_RALT, // Mode_switch
    )

  /**
   * Lock keys are not forwarded: characters are taken from the IDs the server
   * already resolved (including Caps Lock), so Android's own lock state must
   * stay off or it would apply caps a second time.
   */
  val IGNORED_KEYS: Set<Int> =
    setOf(
      0xEFE5, // Caps_Lock
      0xEFE6, // Shift_Lock
      0xEF7F, // Num_Lock
      0xEF14, // Scroll_Lock
    )

  /** Non-character keys: Deskflow key ID → HID usage. */
  val SPECIAL_KEYS: Map<Int, Int> = buildMap {
    put(0xEF08, 0x2A) // BackSpace
    put(0xEF09, 0x2B) // Tab
    put(0xEF0D, 0x28) // Return
    put(0xEF13, 0x48) // Pause
    put(0xEF15, 0x46) // Sys_Req
    put(0xEF1B, 0x29) // Escape
    put(0xEFFF, 0x4C) // Delete
    put(0xEF50, 0x4A) // Home
    put(0xEF51, 0x50) // Left
    put(0xEF52, 0x52) // Up
    put(0xEF53, 0x4F) // Right
    put(0xEF54, 0x51) // Down
    put(0xEF55, 0x4B) // Page_Up
    put(0xEF56, 0x4E) // Page_Down
    put(0xEF57, 0x4D) // End
    put(0xEF61, 0x46) // Print
    put(0xEF63, 0x49) // Insert
    put(0xEF65, 0x7A) // Undo
    put(0xEF66, 0x79) // Redo (HID "Again")
    put(0xEF67, 0x65) // Menu (HID "Application")
    put(0xEF68, 0x7E) // Find
    put(0xEF6A, 0x75) // Help
    put(0xEF6B, 0x48) // Break
    // F1..F12 → 0x3A..0x45, F13..F24 → 0x68..0x73
    for (i in 0 until 12) put(0xEFBE + i, 0x3A + i)
    for (i in 0 until 12) put(0xEFCA + i, 0x68 + i)
    // Keypad navigation / editing keys (Num Lock off on the server)
    put(0xEF80, 0x2C) // KP_Space
    put(0xEF89, 0x2B) // KP_Tab
    put(0xEF8D, 0x58) // KP_Enter
    put(0xEF95, 0x4A) // KP_Home
    put(0xEF96, 0x50) // KP_Left
    put(0xEF97, 0x52) // KP_Up
    put(0xEF98, 0x4F) // KP_Right
    put(0xEF99, 0x51) // KP_Down
    put(0xEF9A, 0x4B) // KP_Page_Up
    put(0xEF9B, 0x4E) // KP_Page_Down
    put(0xEF9C, 0x4D) // KP_End
    put(0xEF9E, 0x49) // KP_Insert
    put(0xEF9F, 0x4C) // KP_Delete
    // Media keys. Linux hid-input maps keyboard-page 0x7F-0x81 to
    // mute/volume and 0xE8-0xEB to play/stop/previous/next.
    put(0xE0AD, 0x7F) // AudioMute
    put(0xE0AE, 0x81) // AudioDown
    put(0xE0AF, 0x80) // AudioUp
    put(0xE0B0, 0xEB) // AudioNext
    put(0xE0B1, 0xEA) // AudioPrev
    put(0xE0B2, 0xE9) // AudioStop
    put(0xE0B3, 0xE8) // AudioPlay
  }

  /**
   * Keypad keys that produce characters (Num Lock on at the server). They are
   * typed as the plain character, because Android's own Num Lock state on the
   * virtual device starts off.
   */
  val KEYPAD_CHARS: Map<Int, Char> = buildMap {
    for (i in 0..9) put(0xEFB0 + i, '0' + i)
    put(0xEFAA, '*')
    put(0xEFAB, '+')
    put(0xEFAC, ',')
    put(0xEFAD, '-')
    put(0xEFAE, '.')
    put(0xEFAF, '/')
    put(0xEFBD, '=')
  }

  /**
   * Physical key positions as (US-QWERTY keycode, HID usage). The keycode is
   * the "location" Android's getKeyCodeForKeyLocation expects.
   */
  private val LOCATIONS: List<Pair<Int, Int>> = buildList {
    for (i in 0 until 26) add(KeyEvent.KEYCODE_A + i to 0x04 + i)
    for (i in 1..9) add(KeyEvent.KEYCODE_1 + i - 1 to 0x1E + i - 1)
    add(KeyEvent.KEYCODE_0 to 0x27)
    add(KeyEvent.KEYCODE_SPACE to 0x2C)
    add(KeyEvent.KEYCODE_MINUS to 0x2D)
    add(KeyEvent.KEYCODE_EQUALS to 0x2E)
    add(KeyEvent.KEYCODE_LEFT_BRACKET to 0x2F)
    add(KeyEvent.KEYCODE_RIGHT_BRACKET to 0x30)
    add(KeyEvent.KEYCODE_BACKSLASH to 0x31)
    add(KeyEvent.KEYCODE_SEMICOLON to 0x33)
    add(KeyEvent.KEYCODE_APOSTROPHE to 0x34)
    add(KeyEvent.KEYCODE_GRAVE to 0x35)
    add(KeyEvent.KEYCODE_COMMA to 0x36)
    add(KeyEvent.KEYCODE_PERIOD to 0x37)
    add(KeyEvent.KEYCODE_SLASH to 0x38)
  }

  /** HID usage of the ISO key between left Shift and Z (Linux KEY_102ND). */
  private const val USAGE_ISO_102ND = 0x64

  /** Japanese keys not reachable through a US location. */
  private val EXTRA_KEYCODES: Map<Int, Int> =
    mapOf(KeyEvent.KEYCODE_RO to 0x87, KeyEvent.KEYCODE_YEN to 0x89)

  private val META_COMBOS: List<Pair<Int, Stroke.() -> Stroke>> =
    listOf(
      0 to { this },
      (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON) to { copy(shift = true) },
      (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON) to { copy(altGr = true) },
      (KeyEvent.META_SHIFT_ON or
        KeyEvent.META_SHIFT_LEFT_ON or
        KeyEvent.META_ALT_ON or
        KeyEvent.META_ALT_RIGHT_ON) to { copy(shift = true, altGr = true) },
    )

  /**
   * Build the character → keystroke table for [device] using the layout
   * Android currently applies to it. Dead keys are included as their combining
   * accent character, but a normal key producing the same character wins.
   */
  fun buildCharMap(device: InputDevice): Map<Int, Stroke> {
    val kcm = device.keyCharacterMap
    // Android keycode (after layout) → HID usage
    val keycodeToUsage = LinkedHashMap<Int, Int>()
    for ((location, usage) in LOCATIONS) {
      val keycode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          device.getKeyCodeForKeyLocation(location)
        } else {
          location
        }
      if (keycode != KeyEvent.KEYCODE_UNKNOWN) keycodeToUsage.putIfAbsent(keycode, usage)
    }
    // Keys outside the US location set. Generic.kl maps both Linux key 43 and
    // key 86 (the ISO 102nd key) to BACKSLASH, and the location API resolves
    // BACKSLASH to key 43. Layouts remap key 86 to PLUS (most ISO layouts) or
    // BACKSLASH (e.g. UK, after moving key 43 to POUND); whichever of those is
    // still unclaimed is assumed to be key 86.
    val extraKeys = LinkedHashMap<Int, Int>()
    for (keycode in listOf(KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_BACKSLASH)) {
      if (keycode !in keycodeToUsage) {
        extraKeys[keycode] = USAGE_ISO_102ND
        break
      }
    }
    for ((keycode, usage) in EXTRA_KEYCODES) {
      if (keycode !in keycodeToUsage) extraKeys.putIfAbsent(keycode, usage)
    }

    val chars = HashMap<Int, Stroke>()
    val deadChars = HashMap<Int, Stroke>()
    // Regular positions first, so a character the base layout already offers
    // (e.g. '+' on US Shift+=) never lands on the guessed 102nd key, which on
    // a layout without the remap doesn't produce it.
    for (group in listOf(keycodeToUsage, extraKeys)) {
      for ((meta, apply) in META_COMBOS) {
        for ((keycode, usage) in group) {
          val value = kcm.get(keycode, meta)
          if (value == 0) continue
          val stroke = Stroke(usage, shift = false, altGr = false).apply()
          if (value and KeyCharacterMap.COMBINING_ACCENT != 0) {
            deadChars.putIfAbsent(value and KeyCharacterMap.COMBINING_ACCENT_MASK, stroke)
          } else {
            chars.putIfAbsent(value, stroke)
          }
        }
      }
    }
    for ((ch, stroke) in deadChars) chars.putIfAbsent(ch, stroke)
    return chars
  }

  /** HID modifier bits for a Deskflow modifier mask (see Keyboard.ModifierKeyMask). */
  fun modifiersFromMask(mask: UInt, includeLayoutModifiers: Boolean): Int {
    fun bit(n: Int) = (mask shr n) and 1u == 1u
    var mods = 0
    if (bit(1)) mods = mods or MOD_LCTRL // Control
    if (bit(2)) mods = mods or MOD_LALT // Alt
    if (bit(3) || bit(4)) mods = mods or MOD_LGUI // Meta / Super
    if (includeLayoutModifiers) {
      if (bit(0)) mods = mods or MOD_LSHIFT // Shift
      if (bit(5)) mods = mods or MOD_RALT // AltGr
    }
    return mods
  }
}
