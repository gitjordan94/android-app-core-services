package app.core.services.amplitude.sessionreplay.compose

import androidx.compose.ui.Modifier
import com.amplitude.android.sessionreplay.compose.ampBlock
import com.amplitude.android.sessionreplay.compose.ampMask
import com.amplitude.android.sessionreplay.compose.ampUnmask

/**
 * Apply Session Replay masking to this UI subtree.
 *
 * Typical usage:
 * ```
 * TextField(
 *   value = cardNumber,
 *   onValueChange = { ... },
 *   modifier = Modifier.sessionReplayMask()
 * )
 * ```
 */
fun Modifier.sessionReplayMask(): Modifier = this.ampMask()

/**
 * Explicitly unmask this UI subtree for Session Replay.
 *
 * Typical usage:
 * ```
 * Text(
 *   text = "Non-sensitive label",
 *   modifier = Modifier.sessionReplayUnmask()
 * )
 * ```
 */
fun Modifier.sessionReplayUnmask(): Modifier = this.ampUnmask()

/**
 * Exclude this UI subtree from Session Replay entirely: it is recorded as an opaque block.
 *
 * Use it for content that must not be captured even masked (for example a map with a precise location).
 * A descendant cannot opt back in with [sessionReplayUnmask].
 *
 * Typical usage:
 * ```
 * GoogleMap(
 *   modifier = Modifier.sessionReplayBlock()
 * )
 * ```
 */
fun Modifier.sessionReplayBlock(): Modifier = this.ampBlock()
