package com.bydmate.app.camera

/**
 * A first frame can be black while the camera warms up, so the frame-2 luma alone proves nothing
 * (#409, #397). The picture is measured once more [LATE_LUMA_MS] after the first valid frame
 * ([validFrameAt], 0 while there is none); [done] once that second look was taken.
 */
fun lateLumaDue(validFrameAt: Long, now: Long, done: Boolean): Boolean =
    !done && validFrameAt > 0L && now - validFrameAt >= LATE_LUMA_MS

/** The dump line with the last late luma of each side, `-` where none was measured. */
fun lateLumaDumpLine(left: Int?, right: Int?): String = "late_luma=left:${left ?: "-"} right:${right ?: "-"}"

private const val LATE_LUMA_MS = 1_000L
