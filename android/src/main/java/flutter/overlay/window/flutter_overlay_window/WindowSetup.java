package flutter.overlay.window.flutter_overlay_window;


import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

import io.flutter.plugin.common.BasicMessageChannel;

public abstract class WindowSetup {

    static int height = WindowManager.LayoutParams.MATCH_PARENT;
    static int width = WindowManager.LayoutParams.MATCH_PARENT;
    static int flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    static int gravity = Gravity.CENTER;
    static BasicMessageChannel<Object> messenger = null;
    static String overlayTitle = "Overlay is activated";
    static String overlayContent = "Tap to edit settings or disable";
    static String positionGravity = "none";
    static int notificationVisibility = NotificationCompat.VISIBILITY_PRIVATE;
    static boolean enableDrag = false;

    /**
     * Pikmin fork addition: rectangles (in physical px, relative to this
     * overlay window's own top-left corner — i.e. the same coordinate space
     * as {@link android.view.MotionEvent#getX()}/{@code getY()}, NOT
     * {@code getRawX()}/{@code getRawY()}) inside which
     * {@link OverlayService#onTouch} should NOT engage native window
     * dragging, letting the touch flow through to Flutter's own gesture
     * handling untouched. Set via the new {@code setDragExclusionRects}
     * method on the existing {@code x-slayer/overlay} channel. Empty by
     * default (no exclusions, same as upstream behavior).
     */
    static List<Rect> dragExclusionRects = new ArrayList<>();

    /**
     * Pikmin fork addition (top/left/right drag bounds): physical-px
     * clamp limits applied to {@link WindowManager.LayoutParams#x}/
     * {@code y} inside {@link OverlayService#onTouch}'s
     * {@code ACTION_MOVE} handling, one field per axis so each can be set
     * independently. {@link Integer#MIN_VALUE}/{@link Integer#MAX_VALUE}
     * are the "unset" sentinels — a caller that never calls the new
     * {@code setDragBounds} method (or omits a given key) gets the exact
     * upstream unclamped behavior for that axis, unchanged. Deliberately
     * no {@code dragMaxYPx} — this fork does not clamp the bottom edge
     * (dragging to the bottom of the screen already has a separate,
     * unrelated meaning in the consuming app: closing the whole overlay).
     */
    static int dragMinYPx = Integer.MIN_VALUE;
    static int dragMinXPx = Integer.MIN_VALUE;
    static int dragMaxXPx = Integer.MAX_VALUE;


    static void setNotificationVisibility(String name) {
        if (name.equalsIgnoreCase("visibilityPublic")) {
            notificationVisibility = NotificationCompat.VISIBILITY_PUBLIC;
        }
        if (name.equalsIgnoreCase("visibilitySecret")) {
            notificationVisibility = NotificationCompat.VISIBILITY_SECRET;
        }
        if (name.equalsIgnoreCase("visibilityPrivate")) {
            notificationVisibility = NotificationCompat.VISIBILITY_PRIVATE;
        }
    }

    static void setFlag(String name) {
        if (name.equalsIgnoreCase("flagNotFocusable") || name.equalsIgnoreCase("defaultFlag")) {
            flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (name.equalsIgnoreCase("flagNotTouchable") || name.equalsIgnoreCase("clickThrough")) {
            flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        }
        if (name.equalsIgnoreCase("flagNotTouchModal") || name.equalsIgnoreCase("focusPointer")) {
            flag = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
    }

    static void showWhenLocked(String name) {
        if (name.equalsIgnoreCase("flagNotFocusable") || name.equalsIgnoreCase("defaultFlag")) {
            flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (name.equalsIgnoreCase("flagNotTouchable") || name.equalsIgnoreCase("clickThrough")) {
            flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        }
        if (name.equalsIgnoreCase("flagNotTouchModal") || name.equalsIgnoreCase("focusPointer")) {
            flag = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
    }

    static void setGravityFromAlignment(String alignment) {
        if (alignment.equalsIgnoreCase("topLeft")) {
            gravity = Gravity.TOP | Gravity.LEFT;
            return;
        }
        if (alignment.equalsIgnoreCase("topCenter")) {
            gravity = Gravity.TOP;
        }
        if (alignment.equalsIgnoreCase("topRight")) {
            gravity = Gravity.TOP | Gravity.RIGHT;
            return;
        }

        if (alignment.equalsIgnoreCase("centerLeft")) {
            gravity = Gravity.CENTER | Gravity.LEFT;
            return;
        }
        if (alignment.equalsIgnoreCase("center")) {
            gravity = Gravity.CENTER;
        }
        if (alignment.equalsIgnoreCase("centerRight")) {
            gravity = Gravity.CENTER | Gravity.RIGHT;
            return;
        }

        if (alignment.equalsIgnoreCase("bottomLeft")) {
            gravity = Gravity.BOTTOM | Gravity.LEFT;
            return;
        }
        if (alignment.equalsIgnoreCase("bottomCenter")) {
            gravity = Gravity.BOTTOM;
        }
        if (alignment.equalsIgnoreCase("bottomRight")) {
            gravity = Gravity.BOTTOM | Gravity.RIGHT;
            return;
        }

    }
}
