package app.mawjic.tv

@androidx.compose.runtime.Composable
expect fun GuideVerticalScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
)

/** Desktop-only primary-mouse drag support for the shared horizontal guide timeline. */
expect fun androidx.compose.ui.Modifier.guideTimelineMouseDrag(
    scrollState: androidx.compose.foundation.ScrollState,
): androidx.compose.ui.Modifier
