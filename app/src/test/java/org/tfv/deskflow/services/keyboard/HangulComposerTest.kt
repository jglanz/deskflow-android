package org.tfv.deskflow.services.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class HangulComposerTest {

  /** Types QWERTY keys (uppercase = shift) and returns the resulting text. */
  private fun type(keys: String, backspaces: Int = 0): String {
    val composer = HangulComposer()
    val out = StringBuilder()
    keys.forEach { key ->
      val jamo = composer.jamoFor(key, key.isUpperCase())!!
      out.append(composer.input(jamo).commit)
    }
    repeat(backspaces) { composer.backspace() }
    return out.toString() + composer.composing
  }

  @Test fun simpleSyllables() = assertEquals("안녕하세요", type("dkssudgktpdy"))

  @Test fun finalMovesToNextSyllable() = assertEquals("가나", type("rksk"))

  @Test fun compoundFinal() = assertEquals("닭", type("ekfr"))

  @Test fun compoundFinalSplits() = assertEquals("달가", type("ekfrk"))

  @Test fun compoundVowel() = assertEquals("과", type("rhk"))

  @Test fun shiftedConsonants() = assertEquals("빠", type("Qk"))

  @Test fun doubleConsonantCannotBeFinal() = assertEquals("가ㄸ", type("rkE"))

  @Test fun loneJamo() = assertEquals("ㅋㅋ", type("zz"))

  @Test fun backspaceRemovesLastJamo() = assertEquals("다", type("ekfr", 2))

  @Test fun backspaceCompoundVowel() = assertEquals("고", type("rhk", 1))
}
