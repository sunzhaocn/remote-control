package com.simplerc.app.geometry

data class RectPx(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun valid()=listOf(left,top,width,height).all(Float::isFinite)&&width>0&&height>0
    fun contains(x:Float,y:Float)=valid()&&x>=left&&y>=top&&x<=left+width&&y<=top+height
}
data class RemoteGeometry(val version: Long, val capture: RectPx, val screenWidth: Int, val screenHeight: Int, val rotationDegrees: Int) {
    fun valid()=version>0&&capture.valid()&&screenWidth>0&&screenHeight>0&&capture.left>=0&&capture.top>=0&&
        capture.left+capture.width<=screenWidth&&capture.top+capture.height<=screenHeight&&rotationDegrees in listOf(0,90,180,270)
}
data class NormalizedCoordinate(val x: Float,val y: Float)
data class ScreenCoordinate(val x: Float,val y: Float)

class GeometryMapper {
    // MediaProjection pixels already use the current display orientation. Never rotate them again.
    fun controllerToNormalized(x: Float,y: Float,content: RectPx): NormalizedCoordinate? {
        if (!content.contains(x,y)||!x.isFinite()||!y.isFinite())return null
        val u=(x-content.left)/content.width; val v=(y-content.top)/content.height
        val p=NormalizedCoordinate(u,v)
        return p.takeIf{it.x in 0f..1f&&it.y in 0f..1f}
    }
    fun normalizedToRemote(p:NormalizedCoordinate,g:RemoteGeometry):ScreenCoordinate?{
        if(!g.valid()||p.x !in 0f..1f||p.y !in 0f..1f)return null
        val x=minOf(g.screenWidth-1f,g.capture.left+p.x*g.capture.width)
        val y=minOf(g.screenHeight-1f,g.capture.top+p.y*g.capture.height)
        return ScreenCoordinate(x,y).takeIf{x in 0f..g.screenWidth.toFloat()&&y in 0f..g.screenHeight.toFloat()}
    }
    fun mapGesture(points:List<Pair<Float,Float>>,content:RectPx):List<NormalizedCoordinate>?{
        if(points.isEmpty())return null
        val mapped=points.map{controllerToNormalized(it.first,it.second,content)?:return null}
        return mapped
    }
    fun contentRect(viewWidth:Int,viewHeight:Int,frameWidth:Int,frameHeight:Int):RectPx {
        if(listOf(viewWidth,viewHeight,frameWidth,frameHeight).any { it<=0 })return RectPx(0f,0f,0f,0f)
        val scale=minOf(viewWidth.toFloat()/frameWidth,viewHeight.toFloat()/frameHeight)
        val width=kotlin.math.round(frameWidth*scale).toInt().coerceIn(1,viewWidth)
        val height=kotlin.math.round(frameHeight*scale).toInt().coerceIn(1,viewHeight)
        return RectPx(((viewWidth-width)/2).toFloat(),((viewHeight-height)/2).toFloat(),width.toFloat(),height.toFloat())
    }
    fun frameMatchesGeometry(width:Int,height:Int,g:RemoteGeometry):Boolean =
        width>0&&height>0&&g.valid()&&kotlin.math.abs((width.toDouble()/height)/(g.capture.width/g.capture.height)-1)<0.02
}
