package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RtpParameters

class VideoSenderProfileTest {
    @Test fun explicitTwentyFourFpsPreservesPixelsAndTheExistingBitrateBudget() {
        val p=parameters()
        val plan=ScreenContentVideoPlan.create(1440,3200,ScreenActivity.MEDIA,6.0,24,4_000_000)
        assertTrue(VideoSenderProfile.apply(p,plan))
        assertEquals(24,p.encodings.single().maxFramerate)
        assertEquals(4_000_000,p.encodings.single().maxBitrateBps)
        assertEquals(1.0,p.encodings.single().scaleResolutionDownBy!!,0.0)
        assertEquals(RtpParameters.DegradationPreference.DISABLED,p.degradationPreference)
    }
    private fun parameters(encodings:List<RtpParameters.Encoding> = listOf(RtpParameters.Encoding(null,true,6.0))):RtpParameters =
        RtpParameters::class.java.declaredConstructors.single().apply {isAccessible=true}
            .newInstance("tx",RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE,null,
                emptyList<Any>(),encodings,emptyList<Any>()) as RtpParameters
    @Test fun everyStateDisablesHiddenPixelAndCadenceAdaptation() {
        for(state in ScreenActivity.entries) {
            val p=parameters();val plan=ScreenContentVideoPlan.create(1080,2400,state,3.0,30,300_000)
            assertTrue(VideoSenderProfile.apply(p,plan))
            assertEquals(RtpParameters.DegradationPreference.DISABLED,p.degradationPreference)
            assertEquals(300_000,p.encodings.single().maxBitrateBps)
            assertEquals(30,p.encodings.single().maxFramerate)
            assertEquals(0,p.encodings.single().minBitrateBps)
        }
    }
    @Test fun staleRtpScalingIsResetRatherThanCompoundedWithTextureScaling() {
        val p=parameters();VideoSenderProfile.apply(p,ScreenContentVideoPlan.create(1080,2400,ScreenActivity.MEDIA,1.0,30,4_000_000))
        assertEquals(1.0,p.encodings.single().scaleResolutionDownBy!!,0.0)
    }
    @Test fun textRefinementDoesNotForceTheRouteBitrateToItsMaximum() {
        val p=parameters();VideoSenderProfile.apply(p,ScreenContentVideoPlan.create(1080,2400,ScreenActivity.STILL,1.0,30,600_000,true))
        assertEquals(600_000,p.encodings.single().maxBitrateBps)
        assertEquals(RtpParameters.DegradationPreference.DISABLED,p.degradationPreference)
    }
    @Test fun anEmptySenderCannotBeReportedAsApplied() {
        val p=parameters(emptyList())
        assertFalse(VideoSenderProfile.apply(p,ScreenContentVideoPlan.create(1080,2400,ScreenActivity.MEDIA,1.0,30,4_000_000)))
    }
    @Test fun allEncodingsGetTheSameSingleScaleAndMeasuredBudget() {
        val p=parameters(listOf(RtpParameters.Encoding("a",true,3.0),RtpParameters.Encoding("b",false,6.0)))
        VideoSenderProfile.apply(p,ScreenContentVideoPlan.create(1080,2400,ScreenActivity.INTERACTION,2.0,30,1_000_000))
        assertTrue(p.encodings.all {it.scaleResolutionDownBy==1.0 && it.maxBitrateBps==1_000_000})
        assertFalse(p.encodings[1].active)
    }
}
