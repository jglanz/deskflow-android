package org.tfv.deskflow.services.keyboard

/**
 * Dubeolsik (2-set) Hangul composer.
 *
 * Keys are fed one at a time with [input]; each call returns the text that is
 * now finished (to commit) and the syllable still being composed (to show as
 * composing text). The current syllable is rebuilt from the jamo typed for it,
 * which makes [backspace] remove exactly the last typed jamo.
 */
class HangulComposer {

  data class Result(val commit: String, val composing: String)

  private var keys = mutableListOf<Char>()
  private var cho: Char? = null
  private var jung: Char? = null
  private var jong: Char? = null

  val isComposing: Boolean
    get() = keys.isNotEmpty()

  val composing: String
    get() = render(cho, jung, jong)

  fun reset() {
    keys.clear()
    cho = null
    jung = null
    jong = null
  }

  /** Returns the jamo for a QWERTY key in dubeolsik, or null if none. */
  fun jamoFor(key: Char, shift: Boolean): Char? {
    val lower = key.lowercaseChar()
    if (shift) SHIFT_MAP[lower]?.let { return it }
    return BASE_MAP[lower]
  }

  fun input(jamo: Char): Result {
    val commit = StringBuilder()
    if (isVowel(jamo)) inputVowel(jamo, commit) else inputConsonant(jamo, commit)
    return Result(commit.toString(), composing)
  }

  /** Removes the last typed jamo. Returns false if nothing was composing. */
  fun backspace(): Boolean {
    if (keys.isEmpty()) return false
    val remaining = keys.dropLast(1)
    reset()
    remaining.forEach { input(it) }
    return true
  }

  private fun inputConsonant(c: Char, commit: StringBuilder) {
    when {
      cho == null && jung == null -> cho = c
      jung == null -> {
        // Lone initial consonant followed by another consonant.
        commit.append(composing)
        startNew(cho = c)
        return
      }
      cho == null -> {
        // Lone vowel followed by a consonant.
        commit.append(composing)
        startNew(cho = c)
        return
      }
      jong == null && c in JONG -> jong = c
      jong != null && COMPOUND_JONG.containsKey("$jong$c") ->
        jong = COMPOUND_JONG.getValue("$jong$c")
      else -> {
        commit.append(composing)
        startNew(cho = c)
        return
      }
    }
    keys.add(c)
  }

  private fun inputVowel(v: Char, commit: StringBuilder) {
    val currentJong = jong
    when {
      currentJong != null -> {
        // The final consonant moves to the next syllable.
        val split = SPLIT_JONG[currentJong]
        val kept = split?.first
        val moved = split?.second ?: currentJong
        commit.append(render(cho, jung, kept))
        startNew(cho = moved)
        jung = v
        keys.add(v)
        return
      }
      jung == null -> jung = v
      keys.lastOrNull()?.let { isVowel(it) } == true &&
        COMPOUND_JUNG.containsKey("$jung$v") ->
        jung = COMPOUND_JUNG.getValue("$jung$v")
      else -> {
        commit.append(composing)
        startNew(jung = v)
        return
      }
    }
    keys.add(v)
  }

  private fun startNew(cho: Char? = null, jung: Char? = null) {
    reset()
    this.cho = cho
    this.jung = jung
    cho?.let { keys.add(it) }
    jung?.let { keys.add(it) }
  }

  private fun render(cho: Char?, jung: Char?, jong: Char?): String {
    if (cho != null && jung != null) {
      val code =
        0xAC00 +
          (CHO.indexOf(cho) * 21 + JUNG.indexOf(jung)) * 28 +
          (if (jong == null) 0 else JONG.indexOf(jong) + 1)
      return code.toChar().toString()
    }
    return listOfNotNull(cho, jung, jong).joinToString("")
  }

  companion object {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val JONG = "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    private val BASE_MAP =
      mapOf(
        'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ',
        'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ', 'p' to 'ㅔ',
        'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ',
        'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ', 'l' to 'ㅣ',
        'z' to 'ㅋ', 'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ', 'b' to 'ㅠ',
        'n' to 'ㅜ', 'm' to 'ㅡ',
      )

    private val SHIFT_MAP =
      mapOf(
        'q' to 'ㅃ', 'w' to 'ㅉ', 'e' to 'ㄸ', 'r' to 'ㄲ', 't' to 'ㅆ',
        'o' to 'ㅒ', 'p' to 'ㅖ',
      )

    private val COMPOUND_JUNG =
      mapOf(
        "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
        "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ',
        "ㅡㅣ" to 'ㅢ',
      )

    private val COMPOUND_JONG =
      mapOf(
        "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ',
        "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ',
        "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ',
      )

    private val SPLIT_JONG: Map<Char, Pair<Char, Char>> =
      COMPOUND_JONG.entries.associate { (pair, compound) ->
        compound to (pair[0] to pair[1])
      }

    private fun isVowel(c: Char) = c in JUNG
  }
}
