package org.matrix.vector.manager.ui.components.ambience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.matrix.vector.manager.R

/**
 * What the status header's open space is doing.
 *
 * The header needs breathing room above the status line, and empty space that exists only because
 * a layout needed it reads as a mistake. Giving it something to *be* — a surface that answers when
 * touched — turns the same pixels from an accident into the most characterful part of the app.
 *
 * Every option must stay in the background's role: it draws in the header's own on-container
 * colour at low alpha, never competes with the text over it, and never moves fast enough to pull
 * the eye while someone is reading. [None] exists for people who want none of it, and skips the
 * frame loop entirely rather than animating something invisible.
 */
enum class AmbienceKind(val key: String, val labelRes: Int) {
    /** Snowfall. Tap a flake to burst it; tap empty space and one grows there. */
    Snow("snow", R.string.ambience_snow),
    /** A carved maze with one wanderer in it. Tap to move it, swipe for a new maze. */
    Maze("maze", R.string.ambience_maze),
    /**
     * Signal traces carrying several pulses at once. Tap to fire one, swipe to re-route.
     *
     * Kept beside [Maze] rather than replaced by it because they are opposites and both are worth
     * having: a circuit is a designed path many signals share, a maze is an undesigned one a single
     * wanderer has to solve.
     */
    Circuit("circuit", R.string.ambience_circuit),
    /** Falling code. Hold to stop the rain and pick a glyph out of it; pinch to go deeper. */
    Matrix("matrix", R.string.ambience_matrix),
    None("none", R.string.ambience_none);

    companion object {
        fun from(key: String?): AmbienceKind = entries.firstOrNull { it.key == key } ?: Maze
    }
}

/**
 * A self-contained little simulation.
 *
 * Deliberately mutable and frame-driven rather than built from Compose animations: these have
 * dozens of independent particles with their own lifetimes, which `animate*AsState` models badly.
 * The renderer owns its state, the header owns the clock.
 */
interface AmbienceRenderer {
    /** [dt] is milliseconds since the previous frame. */
    fun update(dt: Float, size: Size)

    fun DrawScope.render(tint: Color)

    fun onTap(position: Offset, size: Size)

    /** A press held down; the surface may freeze, grab something, or both. */
    fun onLongPress(position: Offset, size: Size) {}

    /** The held press ended. */
    fun onRelease() {}

    /**
     * A drag, reported as the movement since the previous event.
     *
     * The increment rather than the distance from where the finger went down, because the surface
     * has no reliable notion of where that was: the transform detector in this version of Compose
     * reports no gesture boundary, so a remembered origin leaked from one drag into the next — the
     * maze rebuilt on a nudge, and the rain hit its speed ceiling on the first flick. A renderer
     * that wants a total accumulates one and decides for itself when it has seen enough.
     */
    fun onDrag(pan: Offset, at: Offset, size: Size) {}

    /**
     * How large this render draws itself, as a multiple of its resting size.
     *
     * Every ambience answers a pinch, and every one answers it the same way: a *scale*, not a
     * camera. Zooming out gives more of the thing — finer drizzle, a bigger maze, more traces —
     * and zooming in gives fewer and larger. Simulating a viewer moving through the field was the
     * first attempt and it read as sliding rather than approaching, because none of these have the
     * parallax cues that would sell the move.
     *
     * The surface owns the number so it can be persisted; the renderer only has to honour it.
     */
    var scale: Float

    /**
     * How fast it moves, as a multiple of its resting speed.
     *
     * Only meaningful where there is continuous motion — the maze wanderer and the circuit pulses
     * move on their own schedule, so they ignore it.
     */
    var speed: Float
        get() = 1f
        set(_) {}

    /**
     * False when nothing is moving, letting the header park the frame loop.
     *
     * A status header is on screen the whole time someone reads the activity feed, so an ambience
     * with nothing to do should cost nothing.
     */
    val isAnimating: Boolean
}

fun rendererFor(kind: AmbienceKind): AmbienceRenderer? =
    when (kind) {
        AmbienceKind.Snow -> SnowRenderer()
        AmbienceKind.Maze -> MazeRenderer()
        AmbienceKind.Circuit -> CircuitRenderer()
        AmbienceKind.Matrix -> MatrixRenderer()
        AmbienceKind.None -> null
    }
