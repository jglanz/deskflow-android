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

package org.tfv.deskflow.ui.components.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.tfv.deskflow.ui.components.IAppState
import org.tfv.deskflow.ui.components.LocalSnackbarHostState
import org.tfv.deskflow.ui.theme.DeskflowTheme

@Composable
fun PreviewDeskflowThemedRoot(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable ColumnScope.(IAppState) -> Unit,
) {
  val snackbarHostState = remember { SnackbarHostState() }

  DeskflowTheme(darkTheme = darkTheme) {
    CompositionLocalProvider(
      LocalSnackbarHostState provides snackbarHostState
    ) {
      PreviewAppState { appState ->
        Scaffold(
          snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        ) { contentPadding ->
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(contentPadding)
              .background(color = MaterialTheme.colorScheme.background)
              .windowInsetsPadding(WindowInsets.Companion.statusBars)
              .imePadding(),

          ) {
            content(appState)
          }
        }
      }
    }
  }
}
