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

package org.tfv.deskflow.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

fun deskflowCardDefaultContainerModifier() =
  Modifier.padding(0.dp).widthIn(200.dp, 500.dp)

fun deskflowCardDefaultContentModifier() = Modifier.fillMaxSize().padding(16.dp)

fun deskflowCardStyleDefaults(
  containerModifier: Modifier = deskflowCardDefaultContainerModifier(),
  contentModifier: Modifier = deskflowCardDefaultContentModifier(),
): DeskflowCardStyle =
  DeskflowCardStyle(
    containerModifier = containerModifier,
    contentModifier = contentModifier,
  )

fun deskflowCardWidgetStyleDefaults(
  containerModifier: Modifier = Modifier,
  contentModifier: Modifier = Modifier.fillMaxSize(),
): DeskflowCardStyle {

  return DeskflowCardStyle(
    containerModifier = containerModifier,
    contentModifier = contentModifier,
  )
}

data class DeskflowCardStyle(
  val containerModifier: Modifier = deskflowCardDefaultContainerModifier(),
  val contentModifier: Modifier = deskflowCardDefaultContentModifier(),
)

@Composable
fun DeskflowCard(
  useHeaderStyle: Boolean = true,
  useFooterStyle: Boolean = true,
  useContentStyle: Boolean = true,
  style: DeskflowCardStyle = deskflowCardStyleDefaults(),
  header: (@Composable () -> Unit)? = null,
  footer: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.(style: DeskflowCardStyle) -> Unit,
) { // Placeholder for actual implementation
  // This would typically involve a Card component with a title and content
  Surface(
    shape = MaterialTheme.shapes.medium,
    shadowElevation = 6.dp,
    color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
    modifier = style.containerModifier,
  ) {
    CompositionLocalProvider(
      LocalContentColor provides MaterialTheme.colorScheme.onSurface
    ) {
      Column {
        if (header != null) {
          if (useHeaderStyle) {
            Column(
              verticalArrangement =
                Arrangement.spacedBy(space = 8.dp, alignment = Alignment.Top),
              modifier = deskflowCardColumnModifierDefaults().padding(16.dp),
            ) {
              header()
            }
            HorizontalDivider(
              modifier = Modifier.padding(vertical = 8.dp)
            )
          } else {
            header()
          }
        }

        if (useContentStyle) {
          Column(
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = style.contentModifier.fillMaxHeight(), // .weight(1f),
          ) {
            content(style)
          }
        } else {
          content(style)
        }

        if (footer != null) {
          if (useFooterStyle) {
            Column(
              modifier = deskflowCardColumnModifierDefaults().padding(16.dp)
            ) {
              footer()
            }
          } else {
            footer()
          }
        }
      }
    }
  }
}

@Composable
fun DeskflowCardTitle(@StringRes textRes: Int) {
  DeskflowText(
    id = textRes,
    style = MaterialTheme.typography.headlineLarge,
    modifier = deskflowTextTitleModifierDefaults()

  )
}

@Composable
fun DeskflowCardSubtitle(
  @StringRes textRes: Int,
  textAlign: TextAlign = TextAlign.Start,
  modifier: Modifier = Modifier.fillMaxWidth()


) {
  DeskflowText(
    id = textRes,
    style = MaterialTheme.typography.titleMedium,
    textAlign = textAlign,
    modifier = modifier,
  )
}

fun deskflowCardColumnModifierDefaults(): Modifier = Modifier.fillMaxWidth()

@Composable
fun DeskflowCardColumn(
  modifier: Modifier = deskflowCardColumnModifierDefaults(),
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier = modifier.padding(top = 0.dp),
    verticalArrangement =
      Arrangement.spacedBy(space = 16.dp, alignment = Alignment.Top),
    horizontalAlignment = Alignment.CenterHorizontally,
    content = content,
  )
}

@Composable
fun DeskflowCardWidget(
  header: @Composable () -> Unit,
  footer: @Composable (() -> Unit)? = null,
  adjustStyleHeight: Boolean = true,
  style: DeskflowCardStyle = deskflowCardWidgetStyleDefaults(),
  content: @Composable ColumnScope.(style: DeskflowCardStyle) -> Unit,
) {
  Box(modifier = style.containerModifier.padding(16.dp)) {
    val cardComponent =
      @Composable {
        DeskflowCard(
          useHeaderStyle = false,
          useFooterStyle = false,
          useContentStyle = false,
          header = header,
          footer = footer,
          content = content,
          style =
            style.copy(
              containerModifier =
                if (adjustStyleHeight) style.containerModifier.fillMaxSize()
                else style.containerModifier.fillMaxWidth(),
              contentModifier =
                if (adjustStyleHeight) style.contentModifier.fillMaxSize()
                else style.contentModifier.fillMaxWidth(),
            ),
        )
      }
    if (adjustStyleHeight) {
      cardComponent()
    } else {
      Column(
        modifier = Modifier.fillMaxWidth(), // .fillMaxHeight(),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
      ) {
        cardComponent()
      }
    }
  }
}

@Composable
fun DeskflowCardWidgetTitle(
  modifier: Modifier = Modifier,
  text: String? = null,
  @StringRes textResId: Int? = null,
  style: TextStyle = MaterialTheme.typography.titleLarge,
  color: Color = Color.Unspecified // Or your default title color
) {
  val titleText = when {
    text != null -> text
    textResId != null -> stringResource(id = textResId)
    else -> throw IllegalArgumentException("Either text or textResId must be provided")
  }

  Text(
    text = titleText,
    modifier = modifier,
    style = style,
    color = color
  )
}