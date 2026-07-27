package org.matrix.vector.manager.ui.components.ambience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.random.Random

/**
 * A maze, with something finding its way through it.
 *
 * The opposite of the circuit beside it, and that is the point of having both: a circuit is a
 * designed path that many signals share, a maze is an undesigned one that a single wanderer has to
 * solve. A pulse choosing at random on a trace reads as a fault; a wanderer choosing at random in a
 * maze is the whole idea.
 *
 * Carved rather than sprinkled — see [build] — then braided open, because a perfect maze is all
 * dead ends and a wanderer in one mostly reverses. **Several openings on both edges** mean there is
 * never one true path and its choices are never forced.
 *
 * **One wanderer at a time.** It enters at an opening, turns at random wherever it has a choice,
 * and only when it leaves through some other opening does the next one set out. A second wanderer
 * released while the first was still going turned the header into traffic, and the whole point of
 * the thing is watching a single decision being made and then another.
 *
 * Tap to drop the wanderer where you touched. Swipe for a different maze.
 */
class MazeRenderer : AmbienceRenderer {

    private companion object {
        const val COLS = 13
        const val ROWS = 5
        /** Cells per second. Slow: this sits behind text somebody is reading. */
        const val SPEED = 3.4f
        /**
         * How often a dead end is opened up again.
         *
         * A perfect maze is all dead ends and one route; braiding some of them away leaves
         * corridors, junctions and loops — the shape a maze on paper actually has — and gives the
         * wanderer real choices to make instead of a single path it cannot deviate from.
         */
        const val BRAID = 0.45f
        const val TRAIL = 14
    }

    /**
     * Walls as edges between cells, held as two grids.
     *
     * `right[x][y]` is the wall between (x, y) and (x + 1, y); `down[x][y]` between (x, y) and
     * (x, y + 1). Storing edges rather than cells is what makes "is this move legal" a single
     * lookup with no bounds arithmetic in the hot path.
     */
    private val right = Array(COLS) { BooleanArray(ROWS) }
    private val down = Array(COLS) { BooleanArray(ROWS) }

    /** Rows where the left and right edges are open. Several of each, by construction. */
    private val leftDoors = mutableListOf<Int>()
    private val rightDoors = mutableListOf<Int>()

    private val random = Random(0x4D5A)
    private var sized = Size.Zero

    private var cx = 0
    private var cy = 0
    private var dx = 1
    private var dy = 0
    /** How far between the current cell and the next, 0..1. */
    private var step = 0f
    private var travelling = false
    private var restDelay = 0f

    private val trail = ArrayDeque<Pair<Int, Int>>()

    override val isAnimating: Boolean
        get() = true

    /**
     * Carves a maze, then loosens it.
     *
     * The walls used to be an independent coin flip per edge, which does not produce a maze — it
     * produces speckle. Random walls leave sealed pockets and open plazas in the same picture, and
     * a wanderer in it looks like it is bouncing around a room rather than working something out.
     *
     * This is a randomised depth-first carve: start everywhere walled, walk to an unvisited
     * neighbour knocking the wall between as you go, and back up when boxed in. What comes out is a
     * *perfect* maze — every cell reachable, exactly one route between any two — which is the
     * structure that reads as a maze at a glance: long corridors, forced turns, junctions.
     *
     * Then it is braided. A perfect maze is all dead ends, and a wanderer in one spends most of its
     * time reversing out of them; opening roughly half the dead ends back up leaves loops, so there
     * is more than one way through and its turns are choices rather than the only legal move.
     */
    private fun build() {
        for (x in 0 until COLS) for (y in 0 until ROWS) {
            right[x][y] = x < COLS - 1
            down[x][y] = y < ROWS - 1
        }

        val visited = Array(COLS) { BooleanArray(ROWS) }
        val stack = ArrayDeque<Pair<Int, Int>>()
        var sx = random.nextInt(COLS)
        var sy = random.nextInt(ROWS)
        visited[sx][sy] = true
        stack.addLast(sx to sy)

        while (stack.isNotEmpty()) {
            val (x, y) = stack.last()
            val unvisited =
                DIRECTIONS.filter { (ddx, ddy) ->
                    val nx = x + ddx
                    val ny = y + ddy
                    nx in 0 until COLS && ny in 0 until ROWS && !visited[nx][ny]
                }
            if (unvisited.isEmpty()) {
                stack.removeLast()
                continue
            }
            val (ddx, ddy) = unvisited.random(random)
            carve(x, y, ddx, ddy)
            sx = x + ddx
            sy = y + ddy
            visited[sx][sy] = true
            stack.addLast(sx to sy)
        }

        for (x in 0 until COLS) for (y in 0 until ROWS) {
            val exits = DIRECTIONS.count { (ddx, ddy) -> open(x, y, ddx, ddy) }
            if (exits <= 1 && random.nextFloat() < BRAID) {
                val closed =
                    DIRECTIONS.filter { (ddx, ddy) ->
                        val nx = x + ddx
                        val ny = y + ddy
                        nx in 0 until COLS && ny in 0 until ROWS && !open(x, y, ddx, ddy)
                    }
                closed.randomOrNull(random)?.let { (ddx, ddy) -> carve(x, y, ddx, ddy) }
            }
        }

        // Doors are punched, not left to chance: a maze whose exits depend on the same draw as its
        // walls can come out sealed, and a sealed maze has nothing to watch.
        leftDoors.clear()
        rightDoors.clear()
        val rows = (0 until ROWS).toMutableList()
        rows.shuffle(random)
        val doorCount = 2 + random.nextInt(2)
        leftDoors += rows.take(doorCount)
        rows.shuffle(random)
        rightDoors += rows.take(doorCount)

        trail.clear()
        travelling = false
        restDelay = 0f
    }

    /** Knocks down the wall between a cell and its neighbour. */
    private fun carve(x: Int, y: Int, ddx: Int, ddy: Int) {
        when {
            ddx == 1 -> right[x][y] = false
            ddx == -1 -> right[x - 1][y] = false
            ddy == 1 -> down[x][y] = false
            ddy == -1 -> down[x][y - 1] = false
        }
    }

    private fun seed(size: Size) {
        if (sized == size && (leftDoors.isNotEmpty() || rightDoors.isNotEmpty())) return
        sized = size
        build()
    }

    /** True when a move from (x, y) in a direction is not blocked by a wall or the top/bottom. */
    private fun open(x: Int, y: Int, ddx: Int, ddy: Int): Boolean =
        when {
            ddx == 1 -> x < COLS - 1 && !right[x][y]
            ddx == -1 -> x > 0 && !right[x - 1][y]
            ddy == 1 -> y < ROWS - 1 && !down[x][y]
            else -> y > 0 && !down[x][y - 1]
        }

    private fun enter() {
        val fromLeft = random.nextBoolean() || rightDoors.isEmpty()
        if (fromLeft && leftDoors.isNotEmpty()) {
            cx = 0
            cy = leftDoors.random(random)
            dx = 1
        } else if (rightDoors.isNotEmpty()) {
            cx = COLS - 1
            cy = rightDoors.random(random)
            dx = -1
        } else {
            // build() always punches doors, so this is unreachable — but returning without
            // arming the delay would retry on every frame forever, which is the wrong way for an
            // impossible branch to fail.
            restDelay = 1_000f
            return
        }
        dy = 0
        step = 0f
        travelling = true
        trail.clear()
        trail.addLast(cx to cy)
    }

    /**
     * Picks the next direction.
     *
     * Every legal move except turning straight back is a candidate and one is taken at random, so
     * the route is decided at each junction rather than planned. Reversing is allowed only from a
     * dead end, which is the one case where there is nothing else to do.
     */
    private fun turn() {
        val options =
            DIRECTIONS.filter { (ndx, ndy) ->
                !(ndx == -dx && ndy == -dy) && open(cx, cy, ndx, ndy)
            }
        val pick =
            when {
                options.isNotEmpty() -> options.random(random)
                // A dead end: about-face rather than stall.
                open(cx, cy, -dx, -dy) -> -dx to -dy
                else -> null
            }
        if (pick == null) {
            travelling = false
            restDelay = 900f
            return
        }
        dx = pick.first
        dy = pick.second
    }

    override fun update(dt: Float, size: Size) {
        if (size.width <= 0f || size.height <= 0f) return
        seed(size)

        if (!travelling) {
            // Only ever one wanderer: the next sets out after this one has left, never beside it.
            restDelay -= dt
            if (restDelay <= 0f) enter()
            return
        }

        step += SPEED * dt / 1000f
        while (step >= 1f) {
            step -= 1f
            val nx = cx + dx
            val ny = cy + dy

            // Leaving through a door on either edge ends the run.
            if (nx < 0 || nx >= COLS) {
                travelling = false
                restDelay = 700f + random.nextFloat() * 900f
                return
            }

            cx = nx
            cy = ny
            trail.addLast(cx to cy)
            while (trail.size > TRAIL) trail.removeFirst()

            // At an edge door, carry straight on out; otherwise choose.
            val leaving =
                (cx == 0 && dx == -1 && cy in leftDoors) ||
                    (cx == COLS - 1 && dx == 1 && cy in rightDoors)
            if (!leaving) turn()
        }
    }

    /**
     * Puts the wanderer where you touched.
     *
     * Not a second wanderer — there is only ever one — and not an edit to the walls. Moving it is
     * the one interaction that makes the maze feel like something you are watching rather than
     * something playing to itself: drop it into a corner you want solved and watch it find its way
     * out from there.
     */
    override fun onTap(position: Offset, size: Size) {
        seed(size)
        val (x, y) = cellAt(position, size) ?: return
        cx = x
        cy = y
        step = 0f
        trail.clear()
        trail.addLast(cx to cy)
        travelling = true
        restDelay = 0f
        // Face somewhere it can actually go, so the first move after the tap is not a reversal.
        val ways = DIRECTIONS.filter { (ddx, ddy) -> open(cx, cy, ddx, ddy) }
        val heading = ways.randomOrNull(random) ?: (1 to 0)
        dx = heading.first
        dy = heading.second
    }

    /** A different maze. Watching one lay itself out is half of what the surface is for. */
    override fun onSwipe(from: Offset, to: Offset, size: Size) {
        if (abs(to.x - from.x) < size.width * 0.12f) return
        seed(size)
        build()
    }

    private fun cellAt(position: Offset, size: Size): Pair<Int, Int>? {
        val w = size.width / COLS
        val h = size.height / ROWS
        if (w <= 0f || h <= 0f) return null
        val x = (position.x / w).toInt().coerceIn(0, COLS - 1)
        val y = (position.y / h).toInt().coerceIn(0, ROWS - 1)
        return x to y
    }

    override fun DrawScope.render(tint: Color) {
        if (sized.width <= 0f) return
        val w = size.width / COLS
        val h = size.height / ROWS
        val stroke = Stroke(width = 1.6f)

        // Walls first, faint: they are the setting, not the subject.
        val wallColor = tint.copy(alpha = 0.16f)
        for (x in 0 until COLS) for (y in 0 until ROWS) {
            if (right[x][y]) {
                drawLine(
                    color = wallColor,
                    start = Offset((x + 1) * w, y * h),
                    end = Offset((x + 1) * w, (y + 1) * h),
                    strokeWidth = stroke.width,
                )
            }
            if (down[x][y]) {
                drawLine(
                    color = wallColor,
                    start = Offset(x * w, (y + 1) * h),
                    end = Offset((x + 1) * w, (y + 1) * h),
                    strokeWidth = stroke.width,
                )
            }
        }

        // The outer frame, minus the doors — which is what makes the openings legible as openings.
        for (y in 0 until ROWS) {
            if (y !in leftDoors) {
                drawLine(wallColor, Offset(0f, y * h), Offset(0f, (y + 1) * h), stroke.width)
            }
            if (y !in rightDoors) {
                drawLine(
                    wallColor,
                    Offset(size.width, y * h),
                    Offset(size.width, (y + 1) * h),
                    stroke.width,
                )
            }
        }
        drawLine(wallColor, Offset(0f, 0f), Offset(size.width, 0f), stroke.width)
        drawLine(
            wallColor,
            Offset(0f, size.height),
            Offset(size.width, size.height),
            stroke.width,
        )

        if (!travelling) return

        // The trail, fading behind the wanderer, so a turn stays legible for a moment after it is
        // taken — the decision is the thing worth seeing.
        trail.forEachIndexed { index, (tx, ty) ->
            val age = (index + 1f) / trail.size
            drawCircle(
                color = tint.copy(alpha = 0.10f * age * age),
                radius = minOf(w, h) * 0.16f,
                center = Offset((tx + 0.5f) * w, (ty + 0.5f) * h),
            )
        }

        val headX = (cx + 0.5f + dx * step) * w
        val headY = (cy + 0.5f + dy * step) * h
        drawCircle(
            color = tint.copy(alpha = 0.42f),
            radius = minOf(w, h) * 0.17f,
            center = Offset(headX, headY),
        )
    }
}

private val DIRECTIONS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
