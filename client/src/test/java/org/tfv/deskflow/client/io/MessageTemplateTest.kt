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

package org.tfv.deskflow.client.io

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class MessageTemplateTest {
    @Test
    fun testSpecifiers() {

        MessageTemplate.DKeyDown.let {
            assertTrue(it.specifiers.size == 3)
            assertTrue(it.specifiers.all { s -> s.size == 2  })
        }

        MessageTemplate.CClipboard.let {
            assertTrue(it.specifiers.size == 2)
            assertTrue(it.specifiers[0].size == 1)
            assertTrue(it.specifiers[1].size == 4)
        }

    }

    /**
     * The wire parser keys messages on a leading literal code (e.g. "DKDN"),
     * which is 4 characters for the Deskflow messages but longer for the legacy
     * Barrier/Synergy handshake ("Barrier", "Synergy"). Every code must be a
     * non-empty alphabetic identifier (no specifier leaked into the code).
     */
    @Test
    fun allTemplatesHaveAlphabeticCode() {
        for (template in MessageTemplate.entries) {
            assertTrue(template.code.isNotEmpty(), "empty code for $template")
            assertTrue(
                template.code.matches(Regex("[A-Za-z]+")),
                "non-alphabetic code for $template: '${template.code}'",
            )
        }
    }

    /**
     * Every template must be retrievable from its own prefix (so a received
     * frame with that code dispatches to the right message class). Where two
     * templates share a code (e.g. legacy variants), the lookup returns the
     * first — so assert the returned code matches rather than strict identity.
     */
    @Test
    fun allTemplatesResolveViaPrefix() {
        for (template in MessageTemplate.entries) {
            val resolved = MessageTemplate.templateFromPrefix(template.prefix)
            assertNotNull(resolved, "no template for prefix '${template.prefix}' ($template)")
            assertEquals(template.code, resolved?.code, "prefix lookup for $template")
        }
    }

}