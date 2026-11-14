package com.nuvio.tv.ui.screens.addon

import androidx.compose.foundation.layout.Column
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 55 C5: D-pad reach of [StripListRow]'s action strip, as Live TV > Sources lays it out (a
 * full-width row, its indented strip, the next full-width row). Shield 2026-11-10: Down from an
 * opened row skipped the strip, so Remove was out of reach.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StripListRowFocusTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var first: FocusRequester

    @Before
    fun setUp() {
        rule.setContent {
            NuvioTheme {
                first = remember { FocusRequester() }
                val second = remember { FocusRequester() }
                Column {
                    StripListRow(
                        focusRequester = first,
                        position = 0,
                        dimmedAtRest = false,
                        actions = {
                            CollectionRowAction(label = "Refresh", onClick = {})
                            CollectionRowAction(label = "Rename", onClick = {})
                            CollectionRowAction(label = "Remove", onClick = {}, destructive = true)
                        }
                    ) { Text("First source") }
                    StripListRow(focusRequester = second, position = 1, dimmedAtRest = false, actions = {}) {
                        Text("Second source")
                    }
                }
            }
        }
        rule.runOnIdle { first.requestFocus() }
        rule.onNodeWithText("First source").assertIsFocused()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun downFromAnOpenedRowEntersItsStripAndRemoveIsReachable() {
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        rule.onNodeWithText("Refresh").assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        rule.onNodeWithText("Remove").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithText("Second source").assertIsFocused()
    }

    @Test
    fun upFromTheStripReturnsToItsRow() {
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        rule.onNodeWithText("First source").assertIsFocused()
    }

    @Test
    fun downFromAClosedRowGoesToTheNextRow() {
        press(Key.DirectionDown)
        rule.onNodeWithText("Second source").assertIsFocused()
    }
}
