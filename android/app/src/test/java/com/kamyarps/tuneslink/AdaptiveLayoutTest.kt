package com.kamyarps.tuneslink

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout choices are made from the window, so they are pure functions of its size. */
class AdaptiveLayoutTest {
    @Test fun layoutModeFollowsWindowWidthAndFontScale() {
        assertEquals(AppLayoutMode.Phone, appLayoutMode(411f, 1f))
        assertEquals(AppLayoutMode.Phone, appLayoutMode(599.9f, 1f))
        // Split screen on a tablet or an unfolded foldable crosses into the workspace at 600 dp.
        assertEquals(AppLayoutMode.TabletWorkspace, appLayoutMode(600f, 1f))
        assertEquals(AppLayoutMode.TabletWorkspace, appLayoutMode(1066f, 1.3f))
        // Large text keeps the single-column rail layout even on a wide window.
        assertEquals(AppLayoutMode.NavigationRail, appLayoutMode(1066f, 1.5f))
        assertEquals(AppLayoutMode.NavigationRail, appLayoutMode(731f, 2f))
        // A phone in landscape at large text stays a phone below 600 dp.
        assertEquals(AppLayoutMode.Phone, appLayoutMode(560f, 2f))
    }

    @Test fun tabletHeaderStacksOnlyWhenNowPlayingWouldCollapse() {
        // 1066 dp landscape tablet (header content 1042 dp): one row.
        assertFalse(tabletHeaderStacksNowPlaying(1042f, 168f, 330f))
        // 600-650 dp windows, where the title used to collapse: two rows.
        assertTrue(tabletHeaderStacksNowPlaying(576f, 152f, 188f))
        assertTrue(tabletHeaderStacksNowPlaying(626f, 152f, 188f))
        // Wide enough again.
        assertFalse(tabletHeaderStacksNowPlaying(676f, 152f, 188f))
    }

    @Test fun predictiveBackScaleIsBoundedByTheGesture() {
        assertEquals(1f, predictiveBackScale(0f))
        assertEquals(0.94f, predictiveBackScale(1f), 0.0001f)
        assertEquals(0.94f, predictiveBackScale(3f), 0.0001f)
        assertEquals(1f, predictiveBackScale(-1f))
    }

    @Test fun keyboardShortcutsMapMediaKeysAndControlChords() {
        assertEquals(KeyboardShortcut.PlayPause, keyboardShortcut(Key.MediaPlayPause, ctrl = false))
        assertEquals(KeyboardShortcut.PlayPause, keyboardShortcut(Key.Spacebar, ctrl = false))
        assertEquals(KeyboardShortcut.Next, keyboardShortcut(Key.MediaNext, ctrl = false))
        assertEquals(KeyboardShortcut.Next, keyboardShortcut(Key.DirectionRight, ctrl = true))
        assertEquals(KeyboardShortcut.Previous, keyboardShortcut(Key.DirectionLeft, ctrl = true))
        assertEquals(KeyboardShortcut.Search, keyboardShortcut(Key.F, ctrl = true))
        // Plain arrows move focus; plain letters type.
        assertNull(keyboardShortcut(Key.DirectionRight, ctrl = false))
        assertNull(keyboardShortcut(Key.F, ctrl = false))
    }
}
