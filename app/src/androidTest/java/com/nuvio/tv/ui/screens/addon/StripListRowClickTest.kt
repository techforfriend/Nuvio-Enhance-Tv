package com.nuvio.tv.ui.screens.addon

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.collection.CollectionRowAction
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 73 S3b: [StripListRow]'s onClick. While set, OK runs it and the strip never shows, also
 * when it was open before onClick was set (a scan started with the pack row's strip open).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StripListRowClickTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun okRunsOnClickAndAnOpenStripCloses() {
        val clicks = mutableIntStateOf(0)
        val scanning = mutableStateOf(false)
        lateinit var row: FocusRequester
        rule.setContent {
            NuvioTheme {
                row = remember { FocusRequester() }
                StripListRow(
                    focusRequester = row,
                    position = 0,
                    dimmedAtRest = false,
                    onClick = if (scanning.value) ({ clicks.intValue++ }) else null,
                    actions = { CollectionRowAction(label = "Scan again", onClick = {}) }
                ) { Text("Channel pack") }
            }
        }
        rule.runOnIdle { row.requestFocus() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNodeWithText("Scan again").assertExists()

        rule.runOnIdle { scanning.value = true }
        rule.onNodeWithText("Scan again").assertDoesNotExist()
        rule.onNodeWithText("Channel pack").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, clicks.intValue)
        rule.onNodeWithText("Scan again").assertDoesNotExist()
        // The scan ended: the strip stays closed until OK opens it again.
        rule.runOnIdle { scanning.value = false }
        rule.onNodeWithText("Scan again").assertDoesNotExist()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.onNodeWithText("Scan again").assertExists()
    }
}
