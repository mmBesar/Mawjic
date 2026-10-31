package app.mawjic.tv

import kotlin.math.abs

/**
 * Resolve a horizontal page-swipe gesture to a bounded navigation index.
 * Positive deltas are physical rightward drags; RTL navigation reverses the
 * forward/backward relationship so swipes follow the visible page order.
 */
internal fun pageSwipeTargetIndex(
    currentIndex: Int,
    horizontalDistancePx: Float,
    thresholdPx: Float,
    isRtl: Boolean,
    pageCount: Int,
): Int? {
    if (pageCount <= 1 || currentIndex !in 0 until pageCount) return null
    if (!horizontalDistancePx.isFinite() || !thresholdPx.isFinite() || thresholdPx <= 0f) return null
    if (abs(horizontalDistancePx) < thresholdPx) return null

    val forward = if (isRtl) horizontalDistancePx > 0f else horizontalDistancePx < 0f
    val targetIndex = (currentIndex + if (forward) 1 else -1).coerceIn(0, pageCount - 1)
    return targetIndex.takeIf { it != currentIndex }
}
