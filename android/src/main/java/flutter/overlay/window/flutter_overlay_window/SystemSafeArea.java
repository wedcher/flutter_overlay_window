package flutter.overlay.window.flutter_overlay_window;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

/**
 * Pikmin fork addition (dynamic drag safe area): the screen-level area an
 * overlay should stay inside so the user can still touch / start a drag on
 * it — i.e. outside the system bars, the display cutout and the MANDATORY
 * system gesture zones (top notification pull-down, bottom home gesture).
 *
 * <p>Deliberately NOT read from the overlay view's own
 * {@code WindowInsets}: those are the overlap between THIS (small,
 * floating) window and the inset sources, so a ball sitting mid-screen
 * gets all zeros. {@link WindowManager#getCurrentWindowMetrics()} reports
 * insets for the whole display area instead — read through a
 * {@code TYPE_APPLICATION_OVERLAY} window context: the app's own
 * Activity/Service/Application contexts follow the APP's configuration,
 * which for a portrait-locked app on a rotated display is a letterboxed
 * sub-rectangle (seen on Pixel 10 Pro: bounds [963,0][1447,1080] in
 * landscape), not the screen the overlay is drawn on.
 *
 * <p>Non-mandatory {@code systemGestures} (the left/right back-gesture
 * strips) are logged but NOT part of the effective area: excluding them
 * would stop the overlay from sitting flush against the left/right edge.
 *
 * <p>All values are physical px in the same coordinate space as
 * {@code WindowManager.LayoutParams.x/y} for a TOP|LEFT gravity window
 * with {@code FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS} (origin =
 * display top-left).
 */
final class SystemSafeArea {
    /** Display bounds (px). */
    final Rect bounds;
    /** Effective per-edge insets actually used for clamping (px). */
    final Edges effective;
    /** Raw inputs, for logging only (null on the fallback path). */
    final Edges systemBars;
    final Edges mandatoryGestures;
    final Edges cutout;
    final Edges systemGestures;
    /** "metrics" (API 30+ WindowMetrics) or "fallback" (dimen resources). */
    final String source;

    private SystemSafeArea(Rect bounds, Edges effective, Edges systemBars, Edges mandatoryGestures,
                           Edges cutout, Edges systemGestures, String source) {
        this.bounds = bounds;
        this.effective = effective;
        this.systemBars = systemBars;
        this.mandatoryGestures = mandatoryGestures;
        this.cutout = cutout;
        this.systemGestures = systemGestures;
        this.source = source;
    }

    /**
     * @param fallbackTopPx app-supplied top protection used ONLY when the
     *                      insets can't be read (API &lt; 30 or an error);
     *                      never combined with real metrics.
     */
    static SystemSafeArea compute(Context context, int fallbackTopPx) {
        WindowManager wm = (WindowManager) overlayContext(context).getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            try {
                WindowMetrics metrics = wm.getCurrentWindowMetrics();
                WindowInsets insets = metrics.getWindowInsets();
                // IgnoringVisibility: a full-screen game hides the bars, but
                // the bounds must not jump every time the bars show/hide.
                Edges bars = Edges.of(insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()));
                Edges mandatory = Edges.of(insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.mandatorySystemGestures()));
                Edges cut = Edges.of(insets.getInsets(WindowInsets.Type.displayCutout()));
                Edges gestures = Edges.of(insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures()));
                Edges effective = Edges.max(Edges.max(bars, mandatory), cut);
                return new SystemSafeArea(new Rect(metrics.getBounds()), effective, bars, mandatory, cut,
                        gestures, "metrics");
            } catch (RuntimeException e) {
                android.util.Log.w("PIKMIN_SAFE", "WindowMetrics insets failed, using fallback", e);
            }
        }
        Resources res = context.getResources();
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(dm);
        } else {
            dm = res.getDisplayMetrics();
        }
        int top = Math.max(dimen(res, "status_bar_height"), Math.max(fallbackTopPx, 0));
        int bottom = dimen(res, "navigation_bar_height");
        return new SystemSafeArea(new Rect(0, 0, dm.widthPixels, dm.heightPixels),
                new Edges(0, top, 0, bottom), null, null, null, null, "fallback");
    }

    private static Context overlayWindowContext;

    /** One cached overlay window context per process (API 30+). */
    private static Context overlayContext(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return context;
        }
        if (overlayWindowContext == null) {
            try {
                Context app = context.getApplicationContext();
                DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
                Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                overlayWindowContext = app.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            } catch (RuntimeException e) {
                android.util.Log.w("PIKMIN_SAFE", "overlay window context failed", e);
                return context;
            }
        }
        return overlayWindowContext;
    }

    private static int dimen(Resources res, String name) {
        int id = res.getIdentifier(name, "dimen", "android");
        return id > 0 ? res.getDimensionPixelSize(id) : 0;
    }

    /**
     * Plain int per-edge insets; {@code android.graphics.Insets} is API 29+
     * and the fallback path must run on older devices.
     */
    static final class Edges {
        final int left, top, right, bottom;

        Edges(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @android.annotation.TargetApi(Build.VERSION_CODES.Q)
        static Edges of(android.graphics.Insets i) {
            return new Edges(i.left, i.top, i.right, i.bottom);
        }

        static Edges max(Edges a, Edges b) {
            return new Edges(Math.max(a.left, b.left), Math.max(a.top, b.top),
                    Math.max(a.right, b.right), Math.max(a.bottom, b.bottom));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Edges)) return false;
            Edges e = (Edges) o;
            return left == e.left && top == e.top && right == e.right && bottom == e.bottom;
        }

        @Override
        public int hashCode() {
            return ((left * 31 + top) * 31 + right) * 31 + bottom;
        }
    }

    /** Safe rectangle in screen px (bounds minus effective insets). */
    Rect safeRect() {
        return new Rect(bounds.left + effective.left, bounds.top + effective.top,
                bounds.right - effective.right, bounds.bottom - effective.bottom);
    }

    boolean sameAs(SystemSafeArea other) {
        return other != null && bounds.equals(other.bounds) && effective.equals(other.effective);
    }

    @Override
    public String toString() {
        return "source=" + source
                + " bounds=" + bounds.toShortString()
                + " systemBars=" + fmt(systemBars)
                + " mandatoryGestures=" + fmt(mandatoryGestures)
                + " cutout=" + fmt(cutout)
                + " systemGestures(info)=" + fmt(systemGestures)
                + " effective=" + fmt(effective)
                + " safeRect=" + safeRect().toShortString();
    }

    private static String fmt(Edges i) {
        return i == null ? "n/a" : "[l" + i.left + " t" + i.top + " r" + i.right + " b" + i.bottom + "]";
    }
}
