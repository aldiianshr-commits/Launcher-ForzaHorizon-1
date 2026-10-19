package id.fhorizon.mobile.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Where the overlay's input goes: the SDL virtual pad in game, nowhere in the editor. */
interface TouchOutput {
    fun button(keycode: Int, pressed: Boolean)
    /** Sticks -1..1, pedals 0..1. */
    fun axis(axis: Int, value: Float)
}

/**
 * Draws the on-screen controls and turns touches into gamepad input.
 *
 * In play mode the activity calls [handle] from dispatchTouchEvent with every
 * touch; a gesture that starts on a control is consumed, anything else goes
 * on to the game's surface (Pinyon Shift's touch-aware menus). In edit mode
 * [handle] moves the touched control instead and reports the selection.
 */
class TouchOverlayView(context: Context) : View(context) {

    var layout: TouchLayout = TouchLayout.defaults()
        set(value) {
            field = value
            invalidate()
        }
    var output: TouchOutput? = null
    var editMode: Boolean = false
    var selectedId: String? = null
        set(value) {
            field = value
            invalidate()
        }
    var onSelected: ((String?) -> Unit)? = null
    var onLayoutChanged: ((TouchLayout) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    /** Pointer id -> what it is holding. */
    private val owners = HashMap<Int, Holding>()
    private val pressed = HashSet<String>()
    private val stickOffsets = HashMap<String, Pair<Float, Float>>()
    private val floatingCenters = HashMap<String, Pair<Float, Float>>()
    private val pedalValues = HashMap<String, Float>()
    private var dpadState = 0  // bit 0 up, 1 down, 2 left, 3 right
    private var lastTouch = SystemClock.uptimeMillis()

    private data class Holding(val controlId: String, val grabDx: Float = 0f, val grabDy: Float = 0f)

    private val shortSide: Float get() = min(width, height).toFloat().coerceAtLeast(1f)
    private fun radius(c: TouchControl) = c.size * shortSide / 2f
    private fun centerX(c: TouchControl) = floatingCenters[c.id]?.first ?: (c.x * width)
    private fun centerY(c: TouchControl) = floatingCenters[c.id]?.second ?: (c.y * height)

    private fun hidden(): Boolean {
        val seconds = layout.autoHideSeconds
        return !editMode && seconds > 0 && SystemClock.uptimeMillis() - lastTouch > seconds * 1000L && owners.isEmpty()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hidden()) return
        for (c in layout.controls) {
            if (!c.visible && !editMode) continue
            val alpha = (255 * layout.globalOpacity * c.opacity * (if (c.visible) 1f else 0.35f)).toInt().coerceIn(20, 255)
            val cx = centerX(c)
            val cy = centerY(c)
            val r = radius(c)
            val active = c.id in pressed || owners.values.any { it.controlId == c.id }
            fill.color = Color.argb((alpha * if (active) 0.75f else 0.35f).toInt(), 20, 24, 32)
            stroke.color = if (editMode && c.id == selectedId) Color.argb(255, 255, 138, 61)
            else Color.argb(alpha, 232, 234, 240)
            text.color = Color.argb(alpha, 232, 234, 240)
            text.textSize = max(18f, r * if (c.label.length > 2) 0.35f else 0.6f)
            when (c.type) {
                ControlType.STICK -> {
                    canvas.drawCircle(cx, cy, r, fill)
                    canvas.drawCircle(cx, cy, r, stroke)
                    val (dx, dy) = stickOffsets[c.id] ?: (0f to 0f)
                    fill.color = Color.argb(alpha, 61, 139, 255)
                    canvas.drawCircle(cx + dx * r, cy + dy * r, r * 0.38f, fill)
                }
                ControlType.PEDAL -> {
                    val rect = RectF(cx - r * 0.55f, cy - r, cx + r * 0.55f, cy + r)
                    canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, fill)
                    canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, stroke)
                    val value = pedalValues[c.id] ?: 0f
                    if (value > 0f) {
                        fill.color = Color.argb(alpha, if (c.code == TouchLayout.AXIS_RT) 61 else 255, if (c.code == TouchLayout.AXIS_RT) 200 else 90, 90)
                        canvas.drawRoundRect(RectF(rect.left, rect.bottom - rect.height() * value, rect.right, rect.bottom), r * 0.2f, r * 0.2f, fill)
                    }
                    canvas.drawText(c.label, cx, cy + text.textSize / 3, text)
                }
                ControlType.DPAD -> {
                    val arm = r * 0.36f
                    for ((index, offset) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).withIndex()) {
                        val bx = cx + offset.first * (r - arm)
                        val by = cy + offset.second * (r - arm)
                        fill.color = Color.argb((alpha * if ((dpadState and (1 shl index)) != 0) 0.8f else 0.35f).toInt(), 20, 24, 32)
                        canvas.drawCircle(bx, by, arm, fill)
                        canvas.drawCircle(bx, by, arm, stroke)
                    }
                }
                ControlType.BUTTON -> {
                    canvas.drawCircle(cx, cy, r, fill)
                    canvas.drawCircle(cx, cy, r, stroke)
                    canvas.drawText(c.label, cx, cy + text.textSize / 3, text)
                }
            }
        }
    }

    private fun hit(x: Float, y: Float): TouchControl? {
        var best: TouchControl? = null
        var bestDistance = Float.MAX_VALUE
        for (c in layout.controls) {
            if (!c.visible && !editMode) continue
            val distance = hypot(x - c.x * width, y - c.y * height)
            val reach = radius(c) * if (c.type == ControlType.STICK && layout.floatingLeftStick && c.id == "ls" && !editMode) 1.8f else 1.15f
            if (distance <= reach && distance < bestDistance) {
                best = c
                bestDistance = distance
            }
        }
        // The floating left stick also takes any touch in the lower-left quarter.
        if (best == null && !editMode && layout.floatingLeftStick && x < width * 0.4f && y > height * 0.4f) {
            best = layout.controls.firstOrNull { it.id == "ls" && it.visible }
        }
        return best
    }

    /** Returns true when the event belongs to the overlay. */
    fun handle(event: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(index)
                val y = event.getY(index)
                val control = hit(x, y) ?: run {
                    if (editMode && event.actionMasked == MotionEvent.ACTION_DOWN) {
                        selectedId = null; onSelected?.invoke(null)
                    }
                    return owners.isNotEmpty()
                }
                if (editMode) {
                    owners[pointerId] = Holding(control.id, x - control.x * width, y - control.y * height)
                    selectedId = control.id
                    onSelected?.invoke(control.id)
                    return true
                }
                owners[pointerId] = Holding(control.id)
                if (control.type == ControlType.STICK && control.id == "ls" && layout.floatingLeftStick) {
                    floatingCenters[control.id] = x to y
                }
                if (layout.hapticFeedback) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                press(control, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (owners.isEmpty()) return false
                for (i in 0 until event.pointerCount) {
                    val holding = owners[event.getPointerId(i)] ?: continue
                    val control = layout.controls.firstOrNull { it.id == holding.controlId } ?: continue
                    if (editMode) {
                        val nx = ((event.getX(i) - holding.grabDx) / width).coerceIn(0f, 1f)
                        val ny = ((event.getY(i) - holding.grabDy) / height).coerceIn(0f, 1f)
                        layout = layout.update(control.id) { it.copy(x = nx, y = ny) }
                        onLayoutChanged?.invoke(layout)
                    } else {
                        press(control, event.getX(i), event.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointers = if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    owners.keys.toList()
                } else {
                    listOf(pointerId)
                }
                var owned = false
                for (id in pointers) {
                    val holding = owners.remove(id) ?: continue
                    owned = true
                    if (!editMode) {
                        layout.controls.firstOrNull { it.id == holding.controlId }?.let { release(it) }
                    }
                }
                invalidate()
                return owned || owners.isNotEmpty()
            }
        }
        invalidate()
        return true
    }

    private fun press(c: TouchControl, x: Float, y: Float) {
        val out = output
        when (c.type) {
            ControlType.BUTTON -> if (pressed.add(c.id)) out?.button(c.code, true)
            ControlType.STICK -> {
                val r = radius(c)
                var dx = (x - centerX(c)) / r
                var dy = (y - centerY(c)) / r
                val length = hypot(dx, dy)
                if (length > 1f) {
                    dx /= length; dy /= length
                }
                stickOffsets[c.id] = dx to dy
                out?.axis(c.code, dx)
                out?.axis(c.code + 1, dy)
            }
            ControlType.PEDAL -> {
                // Further up the pedal is more throttle; any touch is at least 35 %.
                val r = radius(c)
                val fraction = ((centerY(c) + r - y) / (2 * r)).coerceIn(0f, 1f)
                val value = 0.35f + 0.65f * fraction
                pedalValues[c.id] = value
                out?.axis(c.code, value)
            }
            ControlType.DPAD -> {
                val dx = x - centerX(c)
                val dy = y - centerY(c)
                val dead = radius(c) * 0.2f
                var state = 0
                if (dy < -dead) state = state or 1
                if (dy > dead) state = state or 2
                if (dx < -dead) state = state or 4
                if (dx > dead) state = state or 8
                updateDpad(state)
            }
        }
    }

    private fun release(c: TouchControl) {
        val out = output
        when (c.type) {
            ControlType.BUTTON -> if (pressed.remove(c.id)) out?.button(c.code, false)
            ControlType.STICK -> {
                stickOffsets.remove(c.id)
                floatingCenters.remove(c.id)
                out?.axis(c.code, 0f)
                out?.axis(c.code + 1, 0f)
            }
            ControlType.PEDAL -> {
                pedalValues.remove(c.id)
                out?.axis(c.code, 0f)
            }
            ControlType.DPAD -> updateDpad(0)
        }
    }

    private fun updateDpad(state: Int) {
        val keys = intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        for (bit in 0 until 4) {
            val was = (dpadState and (1 shl bit)) != 0
            val now = (state and (1 shl bit)) != 0
            if (was != now) output?.button(keys[bit], now)
        }
        dpadState = state
    }

    /** Releases everything (pause, focus loss). */
    fun releaseAll() {
        for (holding in owners.values.toList()) {
            layout.controls.firstOrNull { it.id == holding.controlId }?.let { release(it) }
        }
        owners.clear()
        invalidate()
    }
}
