/*
 * FHorizon Mobile - the on-screen touch controls as an SDL gamepad.
 *
 * This file is not part of SDL. It lives in org.libsdl.app because SDL's
 * joystick natives (nativeAddJoystick, onNativePadDown, onNativeJoy) are
 * package-private. SDLControllerManager.pollInputDevices() (patched, see
 * the "FHorizon Mobile patch" comments there) calls onPollInputDevices()
 * whenever SDL scans for controllers, so the virtual pad is announced at the
 * moment SDL's joystick subsystem is ready, like a real controller.
 *
 * SDL builds the pad's gamepad mapping from the button and axis masks given
 * here (SDL_CreateMappingForAndroidGamepad): a/b/x/y, back, start, both
 * stick clicks, both shoulders, the d-pad, two sticks (axes 0-3) and two
 * triggers (axes 4-5, -1 released to +1 fully pressed). The game sees it as
 * an ordinary Xbox-style controller.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.libsdl.app;

import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;

public final class FHVirtualPad {
    private static final String TAG = "FHVirtualPad";

    /** Device id outside the range Android gives input devices. */
    public static final int DEVICE_ID = 0x46480001;  // "FH" 1

    public static final int AXIS_LEFT_X = 0;
    public static final int AXIS_LEFT_Y = 1;
    public static final int AXIS_RIGHT_X = 2;
    public static final int AXIS_RIGHT_Y = 3;
    public static final int AXIS_LEFT_TRIGGER = 4;
    public static final int AXIS_RIGHT_TRIGGER = 5;

    // Bits as in SDLJoystickHandler.getButtonMask(): A B X Y BACK START
    // THUMBL THUMBR L1 R1 DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT.
    private static final int BUTTON_MASK = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4)
            | (1 << 6) | (1 << 7) | (1 << 8) | (1 << 9) | (1 << 10)
            | (1 << 11) | (1 << 12) | (1 << 13) | (1 << 14);
    private static final int AXIS_MASK = 0x003f;

    private static volatile boolean sEnabled = false;
    private static volatile boolean sAdded = false;
    private static volatile boolean sControllerRumble = true;
    private static volatile boolean sDeviceRumble = false;
    private static volatile float sRumbleStrength = 1.0f;
    private static Vibrator sVibrator;

    private FHVirtualPad() {}

    /** Called by the game activity before SDL starts. */
    public static void configure(boolean enabled, boolean controllerRumble, boolean deviceRumble,
                                 float rumbleStrength, Vibrator vibrator) {
        sEnabled = enabled;
        sControllerRumble = controllerRumble;
        sDeviceRumble = deviceRumble;
        sRumbleStrength = Math.max(0f, Math.min(1f, rumbleStrength));
        sVibrator = vibrator;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static boolean isAdded() {
        return sAdded;
    }

    public static boolean isControllerRumbleEnabled() {
        return sControllerRumble;
    }

    static void onPollInputDevices() {
        if (!sEnabled || sAdded || SDLActivity.mBrokenLibraries) return;
        try {
            SDLControllerManager.nativeAddJoystick(DEVICE_ID, "FHorizon Touch Controls",
                    "fhorizon-touch-controls", 0, 0, BUTTON_MASK, 6, AXIS_MASK, 0,
                    true /* rumble: vibrates the tablet when enabled */, false);
            sAdded = true;
            // Triggers rest at -1 (released), as Android reports real triggers.
            SDLControllerManager.onNativeJoy(DEVICE_ID, AXIS_LEFT_TRIGGER, -1f);
            SDLControllerManager.onNativeJoy(DEVICE_ID, AXIS_RIGHT_TRIGGER, -1f);
            Log.i(TAG, "virtual gamepad added");
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "SDL joystick natives unavailable", error);
        }
    }

    /** Press or release a button by Android key code (KEYCODE_BUTTON_A ...). */
    public static void button(int keycode, boolean pressed) {
        if (!sAdded) return;
        if (pressed) {
            SDLControllerManager.onNativePadDown(DEVICE_ID, keycode);
        } else {
            SDLControllerManager.onNativePadUp(DEVICE_ID, keycode);
        }
    }

    /** Sticks -1..1; triggers 0..1 (converted to SDL's -1..1). */
    public static void axis(int axis, float value) {
        if (!sAdded) return;
        float v = value;
        if (axis == AXIS_LEFT_TRIGGER || axis == AXIS_RIGHT_TRIGGER) {
            v = Math.max(0f, Math.min(1f, value)) * 2f - 1f;
        } else {
            v = Math.max(-1f, Math.min(1f, value));
        }
        SDLControllerManager.onNativeJoy(DEVICE_ID, axis, v);
    }

    /** Releases everything (activity paused, overlay hidden). */
    public static void releaseAll() {
        if (!sAdded) return;
        int[] buttons = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
        for (int keycode : buttons) SDLControllerManager.onNativePadUp(DEVICE_ID, keycode);
        for (int a = 0; a < 4; ++a) SDLControllerManager.onNativeJoy(DEVICE_ID, a, 0f);
        SDLControllerManager.onNativeJoy(DEVICE_ID, AXIS_LEFT_TRIGGER, -1f);
        SDLControllerManager.onNativeJoy(DEVICE_ID, AXIS_RIGHT_TRIGGER, -1f);
    }

    /** Rumble for the virtual pad: the tablet's own vibrator, if enabled. */
    static boolean onRumble(int deviceId, float low, float high, int lengthMs) {
        if (deviceId != DEVICE_ID) return false;
        Vibrator vibrator = sVibrator;
        if (!sDeviceRumble || vibrator == null || !vibrator.hasVibrator()) return true;
        float intensity = Math.max(low, high) * sRumbleStrength;
        if (intensity <= 0.01f) {
            vibrator.cancel();
            return true;
        }
        int amplitude = Math.max(1, Math.min(255, Math.round(intensity * 255f)));
        long duration = Math.max(10, Math.min(lengthMs, 1000));
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
        } else {
            vibrator.vibrate(duration);
        }
        return true;
    }

    static boolean onRumbleStop(int deviceId) {
        if (deviceId != DEVICE_ID) return false;
        Vibrator vibrator = sVibrator;
        if (vibrator != null) vibrator.cancel();
        return true;
    }
}
