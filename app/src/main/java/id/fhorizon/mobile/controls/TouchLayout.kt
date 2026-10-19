package id.fhorizon.mobile.controls

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

enum class ControlType { STICK, BUTTON, PEDAL, DPAD }

/**
 * One on-screen control. Positions are fractions of the screen (center),
 * sizes a fraction of the screen's short side, so a layout keeps its shape
 * on any resolution.
 */
data class TouchControl(
    val id: String,
    val label: String,
    val type: ControlType,
    /** Android key code for buttons; for sticks/pedals the SDL axis (FHVirtualPad.AXIS_*). */
    val code: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val opacity: Float = 1f,
    val visible: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("x", x.toDouble()).put("y", y.toDouble())
        .put("size", size.toDouble()).put("opacity", opacity.toDouble()).put("visible", visible)
}

data class TouchLayout(
    val controls: List<TouchControl>,
    val globalOpacity: Float = 0.6f,
    /** The left stick re-centres where the thumb lands (within its half of the screen). */
    val floatingLeftStick: Boolean = true,
    val hapticFeedback: Boolean = true,
    /** Hide the controls after this many seconds without touches (0 = never). */
    val autoHideSeconds: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("global_opacity", globalOpacity.toDouble())
        .put("floating_left_stick", floatingLeftStick)
        .put("haptic_feedback", hapticFeedback)
        .put("auto_hide_seconds", autoHideSeconds)
        .put("controls", JSONArray().apply { controls.forEach { put(it.toJson()) } })

    fun update(id: String, transform: (TouchControl) -> TouchControl): TouchLayout =
        copy(controls = controls.map { if (it.id == id) transform(it) else it })

    companion object {
        // Axis indices as in org.libsdl.app.FHVirtualPad.
        const val AXIS_LEFT = 0
        const val AXIS_RIGHT = 2
        const val AXIS_LT = 4
        const val AXIS_RT = 5

        /** A racing layout: steer left, pedals right, face buttons above the pedals. */
        fun defaults(): TouchLayout = TouchLayout(
            controls = listOf(
                TouchControl("ls", "L", ControlType.STICK, AXIS_LEFT, 0.14f, 0.70f, 0.34f),
                TouchControl("rs", "R", ControlType.STICK, AXIS_RIGHT, 0.62f, 0.78f, 0.22f, visible = false),
                TouchControl("rt", "RT", ControlType.PEDAL, AXIS_RT, 0.92f, 0.70f, 0.30f),
                TouchControl("lt", "LT", ControlType.PEDAL, AXIS_LT, 0.79f, 0.76f, 0.24f),
                TouchControl("a", "A", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_A, 0.90f, 0.36f, 0.11f),
                TouchControl("b", "B", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_B, 0.96f, 0.24f, 0.11f),
                TouchControl("x", "X", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_X, 0.84f, 0.24f, 0.11f),
                TouchControl("y", "Y", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_Y, 0.90f, 0.12f, 0.11f),
                TouchControl("lb", "LB", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_L1, 0.08f, 0.10f, 0.11f),
                TouchControl("rb", "RB", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_R1, 0.72f, 0.10f, 0.11f),
                TouchControl("back", "BACK", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_SELECT, 0.40f, 0.06f, 0.08f),
                TouchControl("start", "START", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_START, 0.60f, 0.06f, 0.08f),
                TouchControl("dpad", "D", ControlType.DPAD, 0, 0.30f, 0.40f, 0.22f),
                TouchControl("l3", "L3", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_THUMBL, 0.27f, 0.82f, 0.09f, visible = false),
                TouchControl("r3", "R3", ControlType.BUTTON, KeyEvent.KEYCODE_BUTTON_THUMBR, 0.50f, 0.88f, 0.09f, visible = false),
            ),
        )

        fun fromJson(json: JSONObject?): TouchLayout {
            val base = defaults()
            if (json == null) return base
            val saved = HashMap<String, JSONObject>()
            json.optJSONArray("controls")?.let { array ->
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { saved[it.optString("id")] = it }
            }
            return base.copy(
                globalOpacity = json.optDouble("global_opacity", base.globalOpacity.toDouble()).toFloat().coerceIn(0.1f, 1f),
                floatingLeftStick = json.optBoolean("floating_left_stick", base.floatingLeftStick),
                hapticFeedback = json.optBoolean("haptic_feedback", base.hapticFeedback),
                autoHideSeconds = json.optInt("auto_hide_seconds", base.autoHideSeconds),
                controls = base.controls.map { control ->
                    val s = saved[control.id] ?: return@map control
                    control.copy(
                        x = s.optDouble("x", control.x.toDouble()).toFloat().coerceIn(0f, 1f),
                        y = s.optDouble("y", control.y.toDouble()).toFloat().coerceIn(0f, 1f),
                        size = s.optDouble("size", control.size.toDouble()).toFloat().coerceIn(0.05f, 0.6f),
                        opacity = s.optDouble("opacity", control.opacity.toDouble()).toFloat().coerceIn(0.1f, 1f),
                        visible = s.optBoolean("visible", control.visible),
                    )
                },
            )
        }
    }
}
