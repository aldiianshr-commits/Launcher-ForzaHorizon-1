package id.fhorizon.mobile.controls

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sign

/** The Xbox 360 controller buttons a physical button can be mapped to. */
enum class PadButton(val label: String, val keycode: Int) {
    A("A", KeyEvent.KEYCODE_BUTTON_A),
    B("B", KeyEvent.KEYCODE_BUTTON_B),
    X("X", KeyEvent.KEYCODE_BUTTON_X),
    Y("Y", KeyEvent.KEYCODE_BUTTON_Y),
    LB("LB", KeyEvent.KEYCODE_BUTTON_L1),
    RB("RB", KeyEvent.KEYCODE_BUTTON_R1),
    BACK("Back", KeyEvent.KEYCODE_BUTTON_SELECT),
    START("Start", KeyEvent.KEYCODE_BUTTON_START),
    LS("L3 (klik stik kiri)", KeyEvent.KEYCODE_BUTTON_THUMBL),
    RS("R3 (klik stik kanan)", KeyEvent.KEYCODE_BUTTON_THUMBR),
    DPAD_UP("D-pad atas", KeyEvent.KEYCODE_DPAD_UP),
    DPAD_DOWN("D-pad bawah", KeyEvent.KEYCODE_DPAD_DOWN),
    DPAD_LEFT("D-pad kiri", KeyEvent.KEYCODE_DPAD_LEFT),
    DPAD_RIGHT("D-pad kanan", KeyEvent.KEYCODE_DPAD_RIGHT),
    ;

    companion object {
        fun ofKeycode(keycode: Int): PadButton? = values().firstOrNull { it.keycode == keycode }
    }
}

/**
 * Physical controller settings applied by the game activity before SDL sees
 * the events: button remapping (rewritten KeyEvents), stick dead zone and
 * sensitivity, trigger dead zone (rewritten MotionEvents) and rumble.
 */
data class GamepadMapping(
    /** Physical key code -> target key code. */
    val remap: Map<Int, Int> = emptyMap(),
    val stickDeadZone: Float = 0.10f,
    val stickSensitivity: Float = 1.0f,
    val triggerDeadZone: Float = 0.05f,
    val invertRightY: Boolean = false,
    val swapTriggers: Boolean = false,
    val controllerRumble: Boolean = true,
    /** Vibrate the tablet for on-screen controls when the game rumbles. */
    val deviceRumble: Boolean = true,
    val rumbleStrength: Float = 1.0f,
) {
    val isIdentity: Boolean
        get() = remap.isEmpty() && stickDeadZone <= 0f && stickSensitivity == 1f && triggerDeadZone <= 0f &&
            !invertRightY && !swapTriggers

    fun mapKey(keycode: Int): Int = remap[keycode] ?: keycode

    /** Dead zone and sensitivity on one stick axis value (-1..1). */
    fun shapeStick(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= stickDeadZone) return 0f
        val scaled = ((magnitude - stickDeadZone) / (1f - stickDeadZone)) * stickSensitivity
        return sign(value) * scaled.coerceAtMost(1f)
    }

    /** Dead zone on a trigger value (0..1). */
    fun shapeTrigger(value: Float): Float {
        if (value <= triggerDeadZone) return 0f
        return ((value - triggerDeadZone) / (1f - triggerDeadZone)).coerceIn(0f, 1f)
    }

    fun toJson(): JSONObject {
        val map = JSONObject()
        for ((from, to) in remap) map.put(from.toString(), to)
        return JSONObject().put("remap", map)
            .put("stick_dead_zone", stickDeadZone.toDouble())
            .put("stick_sensitivity", stickSensitivity.toDouble())
            .put("trigger_dead_zone", triggerDeadZone.toDouble())
            .put("invert_right_y", invertRightY)
            .put("swap_triggers", swapTriggers)
            .put("controller_rumble", controllerRumble)
            .put("device_rumble", deviceRumble)
            .put("rumble_strength", rumbleStrength.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject?): GamepadMapping {
            val d = GamepadMapping()
            if (json == null) return d
            val remap = HashMap<Int, Int>()
            json.optJSONObject("remap")?.let { obj ->
                for (key in obj.keys()) {
                    val from = key.toIntOrNull() ?: continue
                    remap[from] = obj.optInt(key, from)
                }
            }
            return GamepadMapping(
                remap = remap,
                stickDeadZone = json.optDouble("stick_dead_zone", d.stickDeadZone.toDouble()).toFloat().coerceIn(0f, 0.5f),
                stickSensitivity = json.optDouble("stick_sensitivity", d.stickSensitivity.toDouble()).toFloat().coerceIn(0.5f, 2f),
                triggerDeadZone = json.optDouble("trigger_dead_zone", d.triggerDeadZone.toDouble()).toFloat().coerceIn(0f, 0.5f),
                invertRightY = json.optBoolean("invert_right_y", d.invertRightY),
                swapTriggers = json.optBoolean("swap_triggers", d.swapTriggers),
                controllerRumble = json.optBoolean("controller_rumble", d.controllerRumble),
                deviceRumble = json.optBoolean("device_rumble", d.deviceRumble),
                rumbleStrength = json.optDouble("rumble_strength", d.rumbleStrength.toDouble()).toFloat().coerceIn(0f, 1f),
            )
        }

        fun isGamepad(source: Int): Boolean =
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        /** A physical controller's button event, remapped; null when unchanged. */
        fun remapKeyEvent(event: KeyEvent, mapping: GamepadMapping): KeyEvent? {
            if (!isGamepad(event.source)) return null
            val target = mapping.mapKey(event.keyCode)
            if (target == event.keyCode) return null
            return KeyEvent(
                event.downTime, event.eventTime, event.action, target, event.repeatCount,
                event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
            )
        }

        private val STICK_AXES = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        private val TRIGGER_AXES = intArrayOf(
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        )

        /** A joystick motion event with stick/trigger shaping applied; null when unchanged. */
        fun shapeMotionEvent(event: MotionEvent, mapping: GamepadMapping): MotionEvent? {
            if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return null
            if (event.actionMasked != MotionEvent.ACTION_MOVE || mapping.isIdentity) return null
            val count = event.pointerCount
            val properties = Array(count) { MotionEvent.PointerProperties() }
            val coords = Array(count) { MotionEvent.PointerCoords() }
            for (p in 0 until count) {
                event.getPointerProperties(p, properties[p])
                event.getPointerCoords(p, coords[p])
                for (axis in STICK_AXES) {
                    var v = mapping.shapeStick(coords[p].getAxisValue(axis))
                    if (axis == MotionEvent.AXIS_RZ && mapping.invertRightY) v = -v
                    coords[p].setAxisValue(axis, v)
                }
                val l = mapping.shapeTrigger(coords[p].getAxisValue(MotionEvent.AXIS_LTRIGGER))
                val r = mapping.shapeTrigger(coords[p].getAxisValue(MotionEvent.AXIS_RTRIGGER))
                val brake = mapping.shapeTrigger(coords[p].getAxisValue(MotionEvent.AXIS_BRAKE))
                val gas = mapping.shapeTrigger(coords[p].getAxisValue(MotionEvent.AXIS_GAS))
                coords[p].setAxisValue(MotionEvent.AXIS_LTRIGGER, if (mapping.swapTriggers) r else l)
                coords[p].setAxisValue(MotionEvent.AXIS_RTRIGGER, if (mapping.swapTriggers) l else r)
                coords[p].setAxisValue(MotionEvent.AXIS_BRAKE, if (mapping.swapTriggers) gas else brake)
                coords[p].setAxisValue(MotionEvent.AXIS_GAS, if (mapping.swapTriggers) brake else gas)
            }
            return MotionEvent.obtain(
                event.downTime, event.eventTime, event.action, count, properties, coords,
                event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
                event.deviceId, event.edgeFlags, event.source, event.flags,
            )
        }

        @Suppress("unused")
        private val ALL_TRIGGERS = TRIGGER_AXES
    }
}
