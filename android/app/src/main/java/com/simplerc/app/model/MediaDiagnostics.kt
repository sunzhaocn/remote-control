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
    val receiveFps: Double? = null,
    val decodeFps: Double? = null,
    val renderFps: Double? = null,
    val receiveKbps: Double? = null,
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
) {
    private fun number(value:Double?):String=value?.let { String.format(Locale.ROOT,"%.1f",it) } ?: "未知"
    private fun efficient(value:Boolean?):String=when(value) {true->"是";false->"否";null->"未知"}
    fun text(role:DeviceRole):String = buildString {
        if(role==DeviceRole.CONTROLLED) {
            append("采集 ${number(captureFps)}fps · 源 ${number(sourceFps)}fps · 发送 ${number(sendFps)}fps\n")
            append("发送 ${number(sendKbps)}kbps · 目标 ${number(targetKbps)}kbps · ${sendWidth ?: "?"}×${sendHeight ?: "?"}\n")
            append("编码 ${sendCodec ?: "未知"} / ${encoder ?: "未知"} · 高效编码 ${efficient(efficientEncoder)}\n")
            append("限制 ${limitation ?: "未知"} · 发送丢帧 ${senderDropped ?: "未知"} · 编码 ${number(encodeMs)}ms\n")
            append("限制累计：${if(limitationSeconds.isEmpty())"未知" else limitationSeconds.entries.joinToString(" / "){"${it.key} ${number(it.value)}s"}}\n")
        } else {
            append("接收 ${number(receiveFps)}fps · 解码 ${number(decodeFps)}fps · 渲染 ${number(renderFps)}fps\n")
            append("接收 ${number(receiveKbps)}kbps · 解码 ${receiveCodec ?: "未知"} / ${decoder ?: "未知"}\n")
            append("高效解码 ${efficient(efficientDecoder)} · 丢帧 ${receiverDropped ?: "未知"}\n")
            append("冻结 ${freezes ?: "未知"}次 / ${number(freezeSeconds)}s · 缓冲 ${number(jitterBufferMs)}ms · 解码 ${number(decodeMs)}ms\n")
        }
        append("估计可用 ${number(availableKbps)}kbps · 候选 ${candidateTypes ?: "未知"}")
    }
}
