package app.mawjic.tv

import kotlin.test.Test
import kotlin.test.assertEquals

class EpgIndexRangeTest {
    private fun programme(title: String, start: Long, end: Long) = Programme(
        channelId = "ch",
        title = title,
        description = "",
        image = "",
        start = start,
        end = end,
    )

    @Test
    fun rangeReturnsOnlyOverlappingProgrammesWithExclusiveEnd() {
        val guide = GuideData(
            channelLogos = emptyMap(),
            channelNames = emptyMap(),
            programmes = listOf(
                programme("long", 0L, 1_000L),
                programme("short-before", 50L, 60L),
                programme("inside", 110L, 150L),
                programme("at-end", 200L, 250L),
                programme("ended-at-start", 70L, 100L),
            ),
        )
        val index = EpgIndex(guide)
        val channel = Channel(id = "ch", name = "Channel", url = "https://example.invalid/ch")
        assertEquals(listOf("long", "inside"), index.programmesInRange(channel, 100L, 200L).map(Programme::title))
        assertEquals(emptyList(), index.programmesInRange(channel, 200L, 200L))
        // The long programme (0..1000) overlaps this range and must be returned.
        assertEquals(listOf("long"), index.programmesInRange(channel, 300L, 400L).map(Programme::title))
        // This range begins after every fixture programme has ended.
        assertEquals(emptyList(), index.programmesInRange(channel, 1_100L, 1_200L))
    }
}
