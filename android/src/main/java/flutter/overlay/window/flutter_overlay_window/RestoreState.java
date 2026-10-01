package flutter.overlay.window.flutter_overlay_window;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.WindowManager;

/**
 * Pikmin fork addition (B39, 2026-10-02): lets {@link OverlayService} survive
 * a process death that the PLAYER did not ask for.
 *
 * <p>The overlay service runs in the same process as the app. When that
 * process is killed (low-memory kill, crash) Android restarts the
 * {@code START_STICKY} service in a NEW process with a {@code null} intent,
 * and every static in {@link WindowSetup} is back to its defaults
 * ({@code MATCH_PARENT} width/height) — creating a window from those would
 * produce a transparent full-screen overlay that swallows every touch.
 *
 * <p>This class persists, in its own SharedPreferences file:
 * <ul>
 *   <li>{@code active}: true while the player has the overlay switched on.
 *       Set by {@code showOverlay}; cleared <b>synchronously</b> on every
 *       explicit close path BEFORE the service is stopped (plugin
 *       {@code closeOverlay}, the service's own close-window intent, the
 *       consuming app's {@code MainActivity.onDestroy()} when finishing).
 *       {@link OverlayService#onDestroy()} clears it again only as a second
 *       layer — a SIGKILL/crash runs none of these, so the flag survives
 *       exactly when the death was not the player's choice.</li>
 *   <li>the window base params handed to {@code showOverlay} (the ball's
 *       size/flag/gravity/drag/notification texts) and the last position
 *       the window had while it was at that base size.</li>
 *   <li>{@code lastRestoreAt}, so a restore that itself dies quickly is not
 *       retried in a loop.</li>
 * </ul>
 * Which overlay form to show after a restore is decided by the Dart side
 * (it asks {@code getRestoreInfo}); this class only brings a SAFE, small
 * window back.
 */
public final class RestoreState {
    private static final String TAG = "PikminRestore";
    private static final String PREFS = "pikmin_overlay_restore";
    private static final String K_ACTIVE = "active";
    private static final String K_WIDTH = "width";
    private static final String K_HEIGHT = "height";
    private static final String K_FLAG = "flag";
    private static final String K_GRAVITY = "gravity";
    private static final String K_ENABLE_DRAG = "enableDrag";
    private static final String K_POSITION_GRAVITY = "positionGravity";
    private static final String K_TITLE = "overlayTitle";
    private static final String K_CONTENT = "overlayContent";
    private static final String K_NOTIFICATION_VISIBILITY = "notificationVisibility";
    private static final String K_HAS_POS = "hasPos";
    private static final String K_POS_X = "posX";
    private static final String K_POS_Y = "posY";
    private static final String K_LAST_RESTORE_AT = "lastRestoreAt";

    /** A restore that dies again within this window is not retried. */
    static final long RESTORE_COOLDOWN_MS = 60_000L;

    private RestoreState() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Called by the plugin's {@code showOverlay} after {@link WindowSetup} is filled. */
    static void saveSession(Context context) {
        prefs(context).edit()
                .putBoolean(K_ACTIVE, true)
                .putInt(K_WIDTH, WindowSetup.width)
                .putInt(K_HEIGHT, WindowSetup.height)
                .putInt(K_FLAG, WindowSetup.flag)
                .putInt(K_GRAVITY, WindowSetup.gravity)
                .putBoolean(K_ENABLE_DRAG, WindowSetup.enableDrag)
                .putString(K_POSITION_GRAVITY, WindowSetup.positionGravity)
                .putString(K_TITLE, WindowSetup.overlayTitle)
                .putString(K_CONTENT, WindowSetup.overlayContent)
                .putInt(K_NOTIFICATION_VISIBILITY, WindowSetup.notificationVisibility)
                .putBoolean(K_HAS_POS, false)
                .commit();
    }

    /**
     * Explicit (player-initiated) end of the overlay session. Synchronous
     * {@code commit()} on purpose: callers may be about to lose the process.
     */
    public static void clearActive(Context context) {
        prefs(context).edit().putBoolean(K_ACTIVE, false).commit();
        Log.d(TAG, "active cleared (explicit close)");
    }

    /**
     * Remember where the window is, but only while it has the base (ball)
     * size — list / cards have other sizes and other origins.
     */
    static void saveBasePositionIfBaseSize(Context context, WindowManager.LayoutParams params) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(K_ACTIVE, false)) return;
        // +-2px: the consuming app's resize-to-ball path rounds dp->px a hair
        // differently from showOverlay (Pixel 3a XL: 132 vs 133).
        if (Math.abs(params.width - p.getInt(K_WIDTH, Integer.MIN_VALUE / 2)) > 2
                || Math.abs(params.height - p.getInt(K_HEIGHT, Integer.MIN_VALUE / 2)) > 2) {
            return;
        }
        p.edit()
                .putBoolean(K_HAS_POS, true)
                .putInt(K_POS_X, params.x)
                .putInt(K_POS_Y, params.y)
                .apply();
    }

    /** Snapshot read at a null-intent restart. */
    static final class Snapshot {
        boolean active;
        int width;
        int height;
        int flag;
        int gravity;
        boolean enableDrag;
        String positionGravity;
        String title;
        String content;
        int notificationVisibility;
        boolean hasPos;
        int posX;
        int posY;
        long lastRestoreAt;

        /**
         * Hard requirement: never build a window that is not a sane, small,
         * explicitly sized one (no MATCH_PARENT / -1 / -1999 / full screen).
         */
        boolean hasSafeWindowSize(int screenW, int screenH) {
            return width > 0 && height > 0 && width < screenW && height < screenH;
        }

        void applyToWindowSetup() {
            WindowSetup.width = width;
            WindowSetup.height = height;
            WindowSetup.flag = flag;
            WindowSetup.gravity = gravity;
            WindowSetup.enableDrag = enableDrag;
            WindowSetup.positionGravity = positionGravity != null ? positionGravity : "none";
            WindowSetup.overlayTitle = title;
            WindowSetup.overlayContent = content != null ? content : "";
            WindowSetup.notificationVisibility = notificationVisibility;
        }
    }

    static Snapshot load(Context context) {
        SharedPreferences p = prefs(context);
        Snapshot s = new Snapshot();
        s.active = p.getBoolean(K_ACTIVE, false);
        s.width = p.getInt(K_WIDTH, 0);
        s.height = p.getInt(K_HEIGHT, 0);
        s.flag = p.getInt(K_FLAG, WindowSetup.flag);
        s.gravity = p.getInt(K_GRAVITY, WindowSetup.gravity);
        s.enableDrag = p.getBoolean(K_ENABLE_DRAG, false);
        s.positionGravity = p.getString(K_POSITION_GRAVITY, "none");
        s.title = p.getString(K_TITLE, WindowSetup.overlayTitle);
        s.content = p.getString(K_CONTENT, "");
        s.notificationVisibility = p.getInt(K_NOTIFICATION_VISIBILITY, WindowSetup.notificationVisibility);
        s.hasPos = p.getBoolean(K_HAS_POS, false);
        s.posX = p.getInt(K_POS_X, 0);
        s.posY = p.getInt(K_POS_Y, 0);
        s.lastRestoreAt = p.getLong(K_LAST_RESTORE_AT, 0L);
        return s;
    }

    static void markRestored(Context context, long now) {
        prefs(context).edit().putLong(K_LAST_RESTORE_AT, now).commit();
    }
}
