package com.simplerc.app.geometry

import org.junit.Assert.*
import org.junit.Test

class RemoteViewportTest {
    private fun fit(frameWidth: Int, frameHeight: Int, width: Int = 1080, height: Int = 2400,
        rotation: Int = 0) = RemoteViewport.fit(900, 800, frameWidth, frameHeight, width, height, rotation)

    @Test fun adaptiveResolutionRetainsTheGestureIdentityButRefreshesDisplayedBounds() {
        val full = fit(576, 1280)
        val reduced = fit(192, 426)
        assertEquals(full.inputSpace, reduced.inputSpace)
        // Even-pixel encoder rounding can move the fitted edge by one pixel. The next
        // gesture must use the new displayed bounds, not a closure of the old frame size.
        assertNotEquals(full.content, reduced.content)
        val mapper = GeometryMapper()
        for (viewport in listOf(full, reduced)) {
            val bounds = viewport.content
            val middle = mapper.controllerToNormalized(bounds.left + bounds.width / 2,
                bounds.top + bounds.height / 2, bounds)!!
            assertEquals(.5f, middle.x, 0f)
            assertEquals(.5f, middle.y, 0f)
        }
    }

    @Test fun realScreenResizeAndRotationInvalidateTheGestureIdentity() {
        val portrait = fit(576, 1280)
        assertNotEquals(portrait.inputSpace, fit(1280, 576, 2400, 1080, 90).inputSpace)
        assertNotEquals(portrait.inputSpace, fit(576, 1280, rotation = 180).inputSpace)
        assertNotEquals(portrait.inputSpace, fit(576, 1280, height = 2200).inputSpace)
    }

    @Test fun missingVideoHasNoTouchableContentBeforeTheFirstFrame() {
        assertFalse(fit(0, 0).content.valid())
        assertFalse(RemoteViewport.fit(0, 0, 576, 1280, 1080, 2400, 0).content.valid())
    }
}
