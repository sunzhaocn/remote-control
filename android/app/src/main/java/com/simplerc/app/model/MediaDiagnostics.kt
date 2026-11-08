package com.simplerc.app.model

import java.util.Locale

/** Only aggregate metrics; no addresses, identities, screen contents or SDP. */
data class MediaDiagnostics(
    val captureFps: Double? = null,
    val sourceFps: Double? = null,
    val sendFps: Double? = null,
    val sendKbps: Double? = null,
    val targetKbps: Double? = null,
    val sendWidth: Long? = null,
    val sendHeight: Long? = null,
    val sendCodec: String? = null,
    val encoder: String? = null,
    val efficientEncoder: Boolean? = null,
    val limitation: String? = null,
    val limitationSeconds: Map<String,Double> = emptyMap(),
    val senderDropped: Long? = null,
    val encodeMs: Double? = null,
    val sendDelayMs: Double? = null,
    val receiveFps: Double? = null,
    val decodeFps: Double? = null,
    val renderFps: Double? = null,
    val receiveKbps: Double? = null,
    val receiveWidth: Long? = null,
    val receiveHeight: Long? = null,
    val receiveCodec: String? = null,
    val decoder: String? = null,
    val efficientDecoder: Boolean? = null,
    val receiverDropped: Long? = null,
    val freezes: Long? = null,
    val freezeSeconds: Double? = null,
    val jitterBufferMs: Double? = null,
    val decodeMs: Double? = null,
    val availableKbps: Double? = null,
    val candidateTypes: String? = null,
    val candidatePairId:String? = null,
    val candidatePairSelection:String? = null,
    val peerLocalCandidateType:String? = null,
    val nativeVideo: com.simplerc.app.webrtc.NativeVideoReading? = null,
    val encodeQp:Double? = null,
    val sendBpp:Double? = null,
    val encodedKeyFrames:Long? = null,
    val decodedKeyFrames:Long? = null,
    val sampledEncodedSizeChanges:Long? = null,
    val outputSizeChanges:Long? = null,
    val applicationRefreshRequests:Long? = null,
    val encodeMsP50:Double? = null,
    val encodeMsP95:Double? = null,
    val encodeMsSamples:Int = 0,
) {
    private fun number(value:Double?):String=value?.let { String.format(Locale.ROOT,"%.1f",it) } ?: "未知"
    private fun efficient(value:Boolean?):String=when(value) {true->"是";false->"否";null->"未知"}
    fun senderSummary():String = "QP均值 ${number(encodeQp)} · 有效bpp ${sendBpp?.let {String.format(Locale.ROOT,"%.3f",it)}?:"未知"} · 编码关键帧 ${encodedKeyFrames?:"未知"} · 输出尺寸变更 ${outputSizeChanges?:"未知"} · 编码尺寸变更(采样) ${sampledEncodedSizeChanges?:"未知"} · 应用刷新请求 ${applicationRefreshRequests?:"未知"} · "+encoderCostSummary()
    fun encoderCostSummary():String = "编码耗时区间均值P50/P95 ${number(encodeMsP50)}/${number(encodeMsP95)}ms（近60秒，${encodeMsSamples}样本，非逐帧分位数） · 编码队列丢帧增量 ${nativeVideo?.encoderDroppedDelta?:"未知"}（${number(nativeVideo?.encoderDroppedIntervalMs)}ms区间）"
    fun text(role:DeviceRole):String = buildString {
        if(role==DeviceRole.CONTROLLED) {
            append("采集 ${number(captureFps)}fps · 源 ${number(sourceFps)}fps · 发送 ${number(sendFps)}fps\n")
            append("发送 ${number(sendKbps)}kbps · 目标 ${number(targetKbps)}kbps · ${sendWidth ?: "?"}×${sendHeight ?: "?"}\n")
            append("编码 ${sendCodec ?: "未知"} / ${encoder ?: "未知"} · 高效编码 ${efficient(efficientEncoder)}\n")
            append("限制 ${limitation ?: "未知"} · 发送丢帧 ${senderDropped ?: "未知"} · 编码 ${number(encodeMs)}ms\n")
            append("发包排队 ${number(sendDelayMs)}ms（非端到端延迟）\n")
            append(senderSummary()+"\n")
            append("限制累计：${if(limitationSeconds.isEmpty())"未知" else limitationSeconds.entries.joinToString(" / "){"${it.key} ${number(it.value)}s"}}\n")
        } else {
            append("接收 ${number(receiveFps)}fps · 解码 ${number(decodeFps)}fps · 渲染 ${number(renderFps)}fps\n")
            append("视频接收 ${number(receiveKbps)}kbps · ${receiveWidth ?: "?"}×${receiveHeight ?: "?"} · 解码 ${receiveCodec ?: "未知"} / ${decoder ?: "未知"}\n")
            append("高效解码 ${efficient(efficientDecoder)} · 丢帧 ${receiverDropped ?: "未知"}\n")
            append("冻结 ${freezes ?: "未知"}次 / ${number(freezeSeconds)}s · 缓冲 ${number(jitterBufferMs)}ms · 解码 ${number(decodeMs)}ms\n")
            append("解码关键帧 ${decodedKeyFrames?:"未知"}\n")
        }
        append("本机发送带宽估计 ${number(availableKbps)}kbps · 媒体候选（本机/对端）${candidateTypes ?: "未知"}")
        append("\n媒体候选对 ${candidatePairId ?: "未知"} · 选择来源 ${candidatePairSelection ?: "未知"}")
        append("\n对端自报媒体本地候选 ${peerLocalCandidateType ?: "未知"}（仅限新鲜会话反馈）")
        append("\n输入到画面延迟：未标定，阶段耗时不能直接相加")
        nativeVideo?.let { n ->
            if(role==DeviceRole.CONTROLLED) {
                append("\n硬编 ${n.encoderName ?: "未知"} · 纹理输入 ${efficient(n.encoderSurface)}")
                append("\n编码队列 ${n.encoderDepth ?: "未知"}/${n.encoderQueueLimit} · 峰值 ${n.encoderPeak ?: "未知"} · 丢帧 ${n.encoderDropped ?: "未知"}")
                if(n.encoderQueueLimit==3)append(" · 3帧兼容上限")
                append("\n实时参数请求 ${efficient(n.encoderOptionsRequested)} · 编码器报告延迟 ${n.encoderLatencyFrames ?: "未知"}帧")
            } else {
                append("\nGPU提交 ${number(n.rendererFps)}fps · 显示前等待 ${number(n.queueMs)}ms（刷新调度 ${number(n.pacingMs)}ms） · 绘制 ${number(n.drawMs)}ms · 换缓冲 ${number(n.swapMs)}ms")
                append("\n渲染替换 ${n.rendererReplaced ?: "未知"} · 过期丢弃 ${n.rendererStale ?: "未知"} · 解码队列峰值 ${n.decoderPeak ?: "未知"}")
                append("\n硬解 ${n.decoderName ?: "未知"} · 低延迟支持 ${efficient(n.decoderLowLatencySupported)} · 请求 ${efficient(n.decoderLowLatencyRequested)}")
            }
            if(n.codecOptionsFallback)append("\n设备拒绝可选实时参数，已回退常规配置")
        }
    }
}
