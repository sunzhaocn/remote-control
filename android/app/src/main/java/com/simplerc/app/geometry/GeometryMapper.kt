package com.simplerc.app.geometry

data class RectPx(val left: Float, val top: Float, val width: Float, val height: Float) { fun valid()=left.isFinite()&&top.isFinite()&&width>0&&height>0 }
data class RemoteGeometry(val version: Long, val capture: RectPx, val screenWidth: Int, val screenHeight: Int, val rotationDegrees: Int)
data class NormalizedCoordinate(val x: Float,val y: Float)
data class ScreenCoordinate(val x: Float,val y: Float)

class GeometryMapper {
    fun controllerToNormalized(x: Float,y: Float,content: RectPx,rotation: Int): NormalizedCoordinate? {
        if (!content.valid()||!x.isFinite()||!y.isFinite()||x<content.left||y<content.top||x>content.left+content.width||y>content.top+content.height)return null
        val u=(x-content.left)/content.width; val v=(y-content.top)/content.height
        val p=when(((rotation%360)+360)%360){0->NormalizedCoordinate(u,v);90->NormalizedCoordinate(v,1-u);180->NormalizedCoordinate(1-u,1-v);270->NormalizedCoordinate(1-v,u);else->return null}
        return p.takeIf{it.x in 0f..1f&&it.y in 0f..1f}
    }
    fun normalizedToRemote(p:NormalizedCoordinate,g:RemoteGeometry):ScreenCoordinate?{
        if(!g.capture.valid()||g.screenWidth<=0||g.screenHeight<=0||p.x !in 0f..1f||p.y !in 0f..1f)return null
        val x=g.capture.left+p.x*g.capture.width; val y=g.capture.top+p.y*g.capture.height
        return ScreenCoordinate(x,y).takeIf{x in 0f..g.screenWidth.toFloat()&&y in 0f..g.screenHeight.toFloat()}
    }
    fun mapGesture(points:List<Pair<Float,Float>>,content:RectPx,rotation:Int):List<NormalizedCoordinate>?{
        if(points.isEmpty())return null
        val mapped=points.map{controllerToNormalized(it.first,it.second,content,rotation)?:return null}
        return mapped
    }
}
