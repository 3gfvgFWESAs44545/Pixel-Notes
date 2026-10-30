/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import moe.rukamori.archivetune.models.MediaMetadata

/**
 * Renders the player's comma-separated artist line. Pixel Notes: khinsider has no artist pages,
 * so names aren't tappable; a long press still runs [onLongClick] (copy artists).
 */
@Composable
fun ClickableArtists(
    artists: List<MediaMetadata.Artist>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val artistLine = remember(artists) { artists.joinToString(", ") { it.name } }

    Text(
        text = artistLine,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            if (onLongClick == null) {
                modifier
            } else {
                modifier.pointerInput(onLongClick) {
                    detectTapGestures(onLongPress = { onLongClick() })
                }
            },
    )
}
