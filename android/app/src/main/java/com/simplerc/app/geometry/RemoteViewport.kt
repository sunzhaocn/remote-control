package com.simplerc.app.geometry

/** Video pixels may change with bandwidth; only the remote screen changes input identity. */
data class RemoteViewport(val inputSpace: InputSpace, val content: RectPx) {
    data class InputSpace(val captureWidth: Int, val captureHeight: Int, val rotation: Int?)

    companion object {
        fun fit(viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int,
            captureWidth: Int, captureHeight: Int, rotation: Int?): RemoteViewport =
            RemoteViewport(InputSpace(captureWidth, captureHeight, rotation),
                GeometryMapper().contentRect(viewWidth, viewHeight, frameWidth, frameHeight))
    }
}
