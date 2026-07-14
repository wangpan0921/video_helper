package com.wangpan.videohelper.util

/**
 * 把录制时长（毫秒）格式化成更友好的中文展示：
 * - 小于 1 分钟：`xxx秒`
 * - 大于等于 1 分钟、小于 60 分钟：`xxx分`
 * - 大于等于 60 分钟：`xxx时xxx分xxx秒`
 *
 * 负数按 0 处理；不足一秒按秒向下取整。
 */
fun formatRecordingDuration(durationMs: Long): String {
    val totalSeconds = (if (durationMs < 0) 0 else durationMs) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        totalSeconds < 60 -> "${totalSeconds}秒"
        totalSeconds < 3600 -> "${totalSeconds / 60}分"
        else -> "${hours}时${minutes}分${seconds}秒"
    }
}
