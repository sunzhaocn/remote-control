package com.simplerc.app.geometry

import org.junit.Assert.*
import org.junit.Test

class GeometryMapperTest {
    private val mapper=GeometryMapper()
    @Test fun capped1080pFramesMapToOriginal2kScreenCoordinatesAfterRotation() {
        for(rotation in listOf(0,90,180,270)) {
            val width=if(rotation%180==0)1440 else 3200
            val height=if(rotation%180==0)3200 else 1440
            val geometry=RemoteGeometry(1,RectPx(0f,0f,width.toFloat(),height.toFloat()),width,height,rotation)
            val plan=com.simplerc.app.webrtc.ScreenContentVideoPlan.create(width,height,
                com.simplerc.app.webrtc.ScreenActivity.INTERACTION,6.0,30,150_000)
            val frame=com.simplerc.app.webrtc.VideoCaptureFormat.forScreen(width,height,plan.captureLongEdge,plan.scale,plan.fps)
            assertTrue(mapper.frameMatchesGeometry(frame.width,frame.height,geometry))
            val content=mapper.contentRect(1200,700,frame.width,frame.height)
            for((u,v) in listOf(0f to 0f,.5f to .5f,1f to 1f,.25f to .75f)) {
                val normalized=mapper.controllerToNormalized(content.left+content.width*u,
                    content.top+content.height*v,content)!!
                val point=mapper.normalizedToRemote(normalized,geometry)!!
                assertEquals(minOf(width-1f,width*u),point.x,.001f)
                assertEquals(minOf(height-1f,height*v),point.y,.001f)
            }
        }
    }
    @Test fun displayedButtonsMapDirectlyInEveryPhysicalOrientation() {
        for(rotation in listOf(0,90,180,270)) {
            val width=if(rotation%180==0)1080 else 2400
            val height=if(rotation%180==0)2400 else 1080
            val geometry=RemoteGeometry(1,RectPx(0f,0f,width.toFloat(),height.toFloat()),width,height,rotation)
            val content=mapper.contentRect(600,800,width,height)
            val point=mapper.controllerToNormalized(content.left+content.width*.25f,content.top+content.height*.75f,content)!!
            val target=mapper.normalizedToRemote(point,geometry)!!
            assertEquals(width*.25f,target.x,0.001f)
            assertEquals(height*.75f,target.y,0.001f)
        }
    }
    @Test fun letterboxingAndAdaptiveVideoResolutionKeepTheSameRemoteButton() {
        val geometry=RemoteGeometry(1,RectPx(0f,0f,1080f,2400f),1080,2400,0)
        for(frame in listOf(576 to 1280,288 to 640)) {
            val content=mapper.contentRect(900,600,frame.first,frame.second)
            assertTrue(content.left>0)
            assertNull(mapper.controllerToNormalized(content.left-1,300f,content))
            val target=mapper.normalizedToRemote(mapper.controllerToNormalized(content.left+content.width/2,300f,content)!!,geometry)!!
            assertEquals(540f,target.x,0.001f);assertEquals(1200f,target.y,0.001f)
        }
    }
    @Test fun oldPortraitFramesCannotUnlockInputAfterLandscapeGeometryArrives() {
        val landscape=RemoteGeometry(2,RectPx(0f,0f,2400f,1080f),2400,1080,90)
        assertFalse(mapper.frameMatchesGeometry(576,1280,landscape))
        assertFalse(mapper.frameMatchesGeometry(0,0,landscape))
        assertTrue(mapper.frameMatchesGeometry(1280,576,landscape))
        assertTrue(mapper.frameMatchesGeometry(640,288,landscape))
    }
    @Test fun invalidBoundsAreRejectedAndOuterEdgeNeverTargetsOutsideTheDisplay() {
        assertNull(mapper.controllerToNormalized(1f,1f,RectPx(0f,0f,Float.POSITIVE_INFINITY,100f)))
        assertNull(mapper.controllerToNormalized(Float.NaN,1f,RectPx(0f,0f,100f,100f)))
        assertNull(mapper.normalizedToRemote(NormalizedCoordinate(.5f,.5f),RemoteGeometry(1,RectPx(10f,0f,100f,100f),100,100,0)))
        val edge=mapper.normalizedToRemote(NormalizedCoordinate(1f,1f),RemoteGeometry(1,RectPx(0f,0f,100f,200f),100,200,0))!!
        assertEquals(99f,edge.x,0f);assertEquals(199f,edge.y,0f)
    }
    @Test fun crossingOutsideContentRejectsTheWholeLegacyGesture() {
        assertNull(mapper.mapGesture(listOf(10f to 10f,101f to 10f),RectPx(0f,0f,100f,100f)))
        assertEquals(2,mapper.mapGesture(listOf(10f to 10f,90f to 90f),RectPx(0f,0f,100f,100f))!!.size)
    }
}
