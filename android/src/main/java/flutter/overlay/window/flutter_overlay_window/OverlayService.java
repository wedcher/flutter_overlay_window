package flutter.overlay.window.flutter_overlay_window;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    private static OverlayService instance;
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel;
    private BasicMessageChannel<Object> overlayMessageChannel;
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    /**
     * Pikmin fork addition: native drag start threshold, in physical px.
     * Upstream hard-coded 5px (~2dp), so natural finger jitter during a
     * stationary long-press started a drag (moved the window) and the
     * consuming app's long-press gesture got cancelled. Uses the platform
     * standard {@link ViewConfiguration#getScaledTouchSlop()} (8dp)
     * instead; read once in {@code onStartCommand}.
     */
    private int touchSlopPx = 5;
    /**
     * Pikmin fork addition (absolute drag mapping): finger raw position and
     * window position at ACTION_DOWN. ACTION_MOVE places the window at
     * {@code anchorParam + (raw - downRaw)} and then clamps, instead of
     * upstream's per-event {@code params += (int) delta}. The incremental
     * version (1) truncated every sub-pixel delta and then advanced lastX,
     * so a slow drag steadily fell behind the finger, and (2) kept
     * advancing lastX while the window was clamped at a bound, leaving a
     * permanent finger/window gap after the finger came back.
     */
    private float downRawX, downRawY;
    private int anchorParamX, anchorParamY;
    /**
     * Pikmin fork addition: the window position this gesture last applied.
     * If params differ at the next ACTION_MOVE, the app moved the window
     * mid-gesture (e.g. the consuming app's long-press close-X mode shifts
     * the window origin while the finger is still down) — the anchor is
     * shifted by the same amount so the drag continues from where the
     * window actually is instead of jumping back.
     */
    private int lastAppliedX, lastAppliedY;
    /**
     * Pikmin fork addition: whether the touch-down point of the CURRENT
     * gesture fell inside one of {@link WindowSetup#dragExclusionRects}.
     * Computed once in {@code ACTION_DOWN} and read (never recomputed)
     * for the rest of that same gesture in {@code ACTION_MOVE}/
     * {@code ACTION_UP}/{@code ACTION_CANCEL} — the classification is
     * fixed for the whole gesture so the drag doesn't flip on/off if the
     * finger later crosses an exclusion rect's boundary mid-gesture.
     */
    private boolean touchInExclusionZone;
    /** Pikmin fork addition: latest screen-level safe area, see {@link #refreshSafeArea}. */
    private SystemSafeArea safeArea;
    /** Pikmin fork addition: the bounds log line is printed once per gesture. */
    private boolean boundsLoggedThisGesture;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");
        if (windowManager != null) {
            windowManager.removeView(flutterView);
            windowManager = null;
            flutterView.detachFromFlutterEngine();
            flutterView = null;
        }
        isRunning = false;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
        instance = null;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mResources = getApplicationContext().getResources();
        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        if (isCloseWindow) {
            if (windowManager != null) {
                windowManager.removeView(flutterView);
                windowManager = null;
                flutterView.detachFromFlutterEngine();
                stopSelf();
            }
            isRunning = false;
            return START_STICKY;
        }
        if (windowManager != null) {
            windowManager.removeView(flutterView);
            windowManager = null;
            flutterView.detachFromFlutterEngine();
            stopSelf();
        }
        isRunning = true;
        touchSlopPx = ViewConfiguration.get(this).getScaledTouchSlop();
        Log.d("onStartCommand", "Service started");
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG));
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterChannel.setMethodCallHandler((call, result) -> {
            if (call.method.equals("updateFlag")) {
                String flag = call.argument("flag").toString();
                updateOverlayFlag(result, flag);
            } else if (call.method.equals("updateOverlayPosition")) {
                int x = call.<Integer>argument("x");
                int y = call.<Integer>argument("y");
                moveOverlay(x, y, result);
            } else if (call.method.equals("resizeOverlay")) {
                int width = call.argument("width");
                int height = call.argument("height");
                boolean enableDrag = call.argument("enableDrag");
                resizeOverlay(width, height, enableDrag, result);
            } else if (call.method.equals("setDragExclusionRects")) {
                List<Map<String, Double>> rects = call.argument("rects");
                setDragExclusionRects(rects, result);
            } else if (call.method.equals("setDragBounds")) {
                Map<String, Object> bounds = call.argument("bounds");
                setDragBounds(bounds, result);
            } else if (call.method.equals("performLongPressHaptic")) {
                // Pikmin fork addition: Flutter's HapticFeedback is a no-op
                // in this Service-hosted engine (no Activity PlatformPlugin),
                // so the overlay asks the overlay view itself. Standard
                // LONG_PRESS constant, honours the system touch-feedback
                // setting, needs no VIBRATE permission.
                boolean ok = flutterView != null
                        && flutterView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                result.success(ok);
            }
        });
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.messenger.send(message);
        });
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            windowManager.getDefaultDisplay().getSize(szWindow);
        } else {
            DisplayMetrics displaymetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(displaymetrics);
            int w = displaymetrics.widthPixels;
            int h = displaymetrics.heightPixels;
            szWindow.set(w, h);
        }
        int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : startX;
        int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : startY;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowSetup.width == -1999 ? -1 : WindowSetup.width,
                WindowSetup.height != -1999 ? WindowSetup.height : screenHeight(),
                0,
                -statusBarHeightPx(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }
        // Pikmin fork addition: disable the system window move animation so
        // programmatic position changes (moveOverlay/updateOverlayPosition)
        // apply instantly instead of visibly sliding across the screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setCanPlayMoveAnimation(false);
        }
        params.gravity = WindowSetup.gravity;
        flutterView.setOnTouchListener(this);
        // Pikmin fork addition (dynamic drag safe area): the view's own
        // insets are only this small window's overlap with the bars, so
        // they are used purely as a "recompute" trigger. Must still hand
        // the insets to the view so FlutterView keeps its own padding.
        flutterView.setOnApplyWindowInsetsListener((v, insets) -> {
            refreshSafeArea("insets");
            return v.onApplyWindowInsets(insets);
        });
        windowManager.addView(flutterView, params);
        refreshSafeArea("attach");
        moveOverlay(dx, dy, null);
        return START_STICKY;
    }


    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return inPortrait() ?
                dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                :
                dm.heightPixels + statusBarHeightPx();
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

            if (statusBarHeightId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
            } else {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }

        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

            if (navBarHeightId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
            } else {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }

        return mNavigationBarHeight;
    }


    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1;
            }
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
            params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
            WindowSetup.enableDrag = enableDrag;
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    /**
     * Pikmin fork addition: replaces {@link WindowSetup#dragExclusionRects}
     * wholesale with the rectangles supplied from Dart. Each entry in
     * {@code rects} is expected to be a map with {@code left}/{@code top}/
     * {@code right}/{@code bottom} keys, all in physical px, relative to
     * this window's own top-left corner (the caller is responsible for
     * converting from Flutter's logical px via the overlay isolate's own
     * {@code devicePixelRatio} before calling — this native side does not
     * do any dp/px conversion here, unlike {@link #moveOverlay} /
     * {@link #resizeOverlay}, because the source coordinates already come
     * from {@code RenderBox.localToGlobal()} in the SAME window's own
     * coordinate space, not a dp value meant for
     * {@code WindowManager.LayoutParams}).
     */
    private void setDragExclusionRects(List<Map<String, Double>> rects, MethodChannel.Result result) {
        List<Rect> parsed = new ArrayList<>();
        if (rects != null) {
            for (Map<String, Double> rect : rects) {
                Double left = rect.get("left");
                Double top = rect.get("top");
                Double right = rect.get("right");
                Double bottom = rect.get("bottom");
                if (left == null || top == null || right == null || bottom == null) {
                    continue;
                }
                parsed.add(new Rect(left.intValue(), top.intValue(), right.intValue(), bottom.intValue()));
            }
        }
        WindowSetup.dragExclusionRects = parsed;
        result.success(true);
    }

    /**
     * Pikmin fork addition (top/left/right drag bounds): sets the
     * {@link WindowSetup#dragMinYPx}/{@code dragMinXPx}/{@code dragMaxXPx}
     * clamp limits used by {@link #onTouch}'s {@code ACTION_MOVE} handling.
     * Each of {@code minY}/{@code minX}/{@code maxX} in {@code bounds} is
     * optional — an omitted key resets that one axis back to its "unset"
     * sentinel (see {@link WindowSetup} field doc), so a caller that only
     * ever wants a top clamp (e.g. the consuming app's list/big-card
     * views, which are deliberately allowed to hang off the left/right
     * edges) can pass just {@code minY} without also having to pass
     * screen-width-derived min/max X values. Same physical-px, window-
     * relative coordinate space as {@link #setDragExclusionRects} — no
     * dp/px conversion happens here, the caller already has
     * {@code devicePixelRatio} for that.
     */
    private void setDragBounds(Map<String, Object> bounds, MethodChannel.Result result) {
        Number minY = bounds == null ? null : (Number) bounds.get("minY");
        Number minX = bounds == null ? null : (Number) bounds.get("minX");
        Number maxX = bounds == null ? null : (Number) bounds.get("maxX");
        WindowSetup.dragMinYPx = minY == null ? Integer.MIN_VALUE : minY.intValue();
        WindowSetup.dragMinXPx = minX == null ? Integer.MIN_VALUE : minX.intValue();
        WindowSetup.dragMaxXPx = maxX == null ? Integer.MAX_VALUE : maxX.intValue();
        // Pikmin fork addition (dynamic drag safe area): optional rule, see
        // WindowSetup#dragSafeContentRect. Omitted = rule off.
        @SuppressWarnings("unchecked")
        Map<String, Object> safe = bounds == null ? null : (Map<String, Object>) bounds.get("safeArea");
        if (safe == null) {
            WindowSetup.dragSafeContentRect = null;
        } else {
            WindowSetup.dragSafeContentRect = new Rect(
                    num(safe, "contentLeft"), num(safe, "contentTop"),
                    num(safe, "contentRight"), num(safe, "contentBottom"));
            WindowSetup.dragSafeLeft = Boolean.TRUE.equals(safe.get("left"));
            WindowSetup.dragSafeTop = Boolean.TRUE.equals(safe.get("top"));
            WindowSetup.dragSafeRight = Boolean.TRUE.equals(safe.get("right"));
            WindowSetup.dragSafeBottom = Boolean.TRUE.equals(safe.get("bottom"));
            WindowSetup.safeAreaFallbackTopPx = num(safe, "fallbackTop");
        }
        result.success(true);
    }

    private static int num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? (int) Math.round(((Number) v).doubleValue()) : 0;
    }

    /**
     * Pikmin fork addition (dynamic drag safe area): re-reads the
     * screen-level {@link SystemSafeArea}. Triggered by attach, the view's
     * insets dispatch, {@link #onConfigurationChanged} and every
     * ACTION_DOWN (one cheap read per gesture — covers a navigation-mode
     * switch even if no callback reached this small window). Logs only
     * when the value changed, or always for the non-touch triggers.
     */
    private void refreshSafeArea(String reason) {
        SystemSafeArea next = SystemSafeArea.compute(this, WindowSetup.safeAreaFallbackTopPx);
        boolean changed = !next.sameAs(safeArea);
        safeArea = next;
        // insets/down fire often (the insets one on every move near an
        // edge), so those only log real changes.
        if (changed || reason.equals("attach") || reason.equals("config")) {
            Log.d("PIKMIN_SAFE", "refresh reason=" + reason + " changed=" + changed + " " + next);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (windowManager != null) {
            refreshSafeArea("config");
        }
    }

    /**
     * Pikmin fork addition: true if {@code (x, y)} — in the same
     * window-relative physical-px coordinate space as
     * {@link MotionEvent#getX()}/{@code getY()} — falls inside any of the
     * currently registered {@link WindowSetup#dragExclusionRects}.
     */
    private boolean isInDragExclusionZone(float x, float y) {
        int ix = (int) x;
        int iy = (int) y;
        for (Rect rect : WindowSetup.dragExclusionRects) {
            if (rect.contains(ix, iy)) {
                return true;
            }
        }
        return false;
    }

    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.x = (x == -1999 || x == -1) ? -1 : dpToPx(x);
            params.y = dpToPx(y);
            windowManager.updateViewLayout(flutterView, params);
            if (result != null)
                result.success(true);
        } else {
            if (result != null)
                result.success(false);
        }
    }


    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            Map<String, Double> position = new HashMap<>();
            position.put("x", instance.pxToDp(params.x));
            position.put("y", instance.pxToDp(params.y));
            return position;
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        if (instance != null && instance.flutterView != null) {
            if (instance.windowManager != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                params.y = instance.dpToPx(y);
                instance.windowManager.updateViewLayout(instance.flutterView, params);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }


    @Override
    public void onCreate() {
        // Get the cached FlutterEngine
        FlutterEngine flutterEngine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);

        if (flutterEngine == null) {
            // Handle the error if engine is not found
            Log.e("OverlayService", "Flutter engine not found, hence creating new flutter engine");
            FlutterEngineGroup engineGroup = new FlutterEngineGroup(this);
            DartExecutor.DartEntrypoint entryPoint = new DartExecutor.DartEntrypoint(
                FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                "overlayMain"
            );  // "overlayMain" is custom entry point

            flutterEngine = engineGroup.createAndRunEngine(this, entryPoint);

            // Cache the created FlutterEngine for future use
            FlutterEngineCache.getInstance().put(OverlayConstants.CACHED_TAG, flutterEngine);
        }

        // Create the MethodChannel with the properly initialized FlutterEngine
        if (flutterEngine != null) {
            flutterChannel = new MethodChannel(flutterEngine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
            overlayMessageChannel = new BasicMessageChannel(flutterEngine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        }

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
        int pendingFlags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags = PendingIntent.FLAG_IMMUTABLE;
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .build();
        startForeground(OverlayConstants.NOTIFICATION_ID, notification);
        instance = this;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType, getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
    }

    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager != null && WindowSetup.enableDrag) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    // Pikmin fork addition: classify the WHOLE upcoming
                    // gesture right here, once, using view-relative
                    // (event.getX()/getY(), NOT getRawX()/getRawY())
                    // coordinates — this is the same coordinate space
                    // Dart's RenderBox.localToGlobal() produces for a
                    // widget inside THIS window, so no absolute-screen
                    // position lookup is needed on either side. Every
                    // other case below only ever READS this field for the
                    // rest of the gesture, never recomputes it.
                    touchInExclusionZone = isInDragExclusionZone(event.getX(), event.getY());
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    downRawX = lastX;
                    downRawY = lastY;
                    anchorParamX = params.x;
                    anchorParamY = params.y;
                    lastAppliedX = params.x;
                    lastAppliedY = params.y;
                    boundsLoggedThisGesture = false;
                    if (WindowSetup.dragSafeContentRect != null) {
                        refreshSafeArea("down");
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (touchInExclusionZone) {
                        return false;
                    }
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && dx * dx + dy * dy < touchSlopPx * touchSlopPx) {
                        return false;
                    }
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    // See lastAppliedX doc: app moved the window mid-gesture.
                    if (params.x != lastAppliedX || params.y != lastAppliedY) {
                        anchorParamX += params.x - lastAppliedX;
                        anchorParamY += params.y - lastAppliedY;
                    }
                    boolean invertX = WindowSetup.gravity == (Gravity.TOP | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.CENTER | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    boolean invertY = WindowSetup.gravity == (Gravity.BOTTOM | Gravity.LEFT)
                            || WindowSetup.gravity == Gravity.BOTTOM
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    int xx = anchorParamX + Math.round(dx) * (invertX ? -1 : 1);
                    int yy = anchorParamY + Math.round(dy) * (invertY ? -1 : 1);
                    // Pikmin fork addition (top/left/right drag bounds):
                    // clamp DURING the gesture, before the value is ever
                    // applied — this is intentionally different from the
                    // consuming app's older "let it cross, then snap back
                    // on a timer" approach for the ball, which the app's
                    // own PROJECT.md records as a deliberate earlier
                    // decision to avoid touching this exact native code.
                    // Unset axes (sentinels, see WindowSetup field doc)
                    // are no-ops, so a window that never calls
                    // setDragBounds — or only sets minY — keeps the exact
                    // upstream unclamped behavior on whichever axes it
                    // didn't set.
                    if (WindowSetup.dragMinXPx != Integer.MIN_VALUE) {
                        xx = Math.max(xx, WindowSetup.dragMinXPx);
                    }
                    if (WindowSetup.dragMaxXPx != Integer.MAX_VALUE) {
                        xx = Math.min(xx, WindowSetup.dragMaxXPx);
                    }
                    if (WindowSetup.dragMinYPx != Integer.MIN_VALUE) {
                        yy = Math.max(yy, WindowSetup.dragMinYPx);
                    }
                    // Pikmin fork addition (dynamic drag safe area): keep the
                    // content rect inside the system safe area on the flagged
                    // edges; max first so min wins when the area is too small.
                    Rect content = WindowSetup.dragSafeContentRect;
                    if (content != null && safeArea != null) {
                        Rect safe = safeArea.safeRect();
                        int sMinX = safe.left - content.left;
                        int sMaxX = safe.right - content.right;
                        int sMinY = safe.top - content.top;
                        int sMaxY = safe.bottom - content.bottom;
                        if (WindowSetup.dragSafeRight) xx = Math.min(xx, sMaxX);
                        if (WindowSetup.dragSafeLeft) xx = Math.max(xx, sMinX);
                        if (WindowSetup.dragSafeBottom) yy = Math.min(yy, sMaxY);
                        if (WindowSetup.dragSafeTop) yy = Math.max(yy, sMinY);
                        if (!boundsLoggedThisGesture) {
                            boundsLoggedThisGesture = true;
                            Log.d("PIKMIN_SAFE", "drag bounds window=" + params.width + "x" + params.height
                                    + " content=" + content.toShortString()
                                    + " edges=" + (WindowSetup.dragSafeLeft ? "L" : "")
                                    + (WindowSetup.dragSafeTop ? "T" : "")
                                    + (WindowSetup.dragSafeRight ? "R" : "")
                                    + (WindowSetup.dragSafeBottom ? "B" : "")
                                    + " minX=" + (WindowSetup.dragSafeLeft ? sMinX : WindowSetup.dragMinXPx)
                                    + " maxX=" + (WindowSetup.dragSafeRight ? sMaxX : WindowSetup.dragMaxXPx)
                                    + " minY=" + (WindowSetup.dragSafeTop ? sMinY : WindowSetup.dragMinYPx)
                                    + " maxY=" + (WindowSetup.dragSafeBottom ? sMaxY : "none")
                                    + " safe=" + safe.toShortString());
                        }
                    }
                    params.x = xx;
                    params.y = yy;
                    lastAppliedX = xx;
                    lastAppliedY = yy;
                    if (windowManager != null) {
                        windowManager.updateViewLayout(flutterView, params);
                    }
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (touchInExclusionZone) {
                        return false;
                    }
                    lastYPosition = params.y;
                    if (dragging) {
                        // Pikmin fork addition (checkpoint 1b — native ->
                        // overlay isolate communication verification): a
                        // real drag (not just a tap) genuinely ended here
                        // — params.x/y already hold the final settled
                        // position (every ACTION_MOVE above already
                        // applied it via updateViewLayout()). Deliberately
                        // does NOT touch WindowSetup.messenger (see the
                        // checkpoint-1 comment history / consuming app's
                        // PROJECT.md "2026-08-21" record for why that
                        // shared static is unreliable) — instead invokes
                        // straight back into THIS SAME overlay engine's
                        // own flutterChannel (already proven reliable for
                        // resizeOverlay/updateFlag/setDragExclusionRects
                        // in the Dart-to-native direction; this is the
                        // same channel, just the reverse direction, which
                        // is a standard MethodChannel capability). No
                        // result callback needed — fire-and-forget.
                        Log.d("OverLay", "Pikmin fork: drag ended at x=" + params.x + " y=" + params.y
                                + ", invoking listDragEnded on flutterChannel");
                        Map<String, Object> listDragEndedArgs = new HashMap<>();
                        listDragEndedArgs.put("x", (double) params.x);
                        listDragEndedArgs.put("y", (double) params.y);
                        flutterChannel.invokeMethod("listDragEnded", listDragEndedArgs);
                    }
                    if (!WindowSetup.positionGravity.equals("none")) {
                        if (windowManager == null) return false;
                        windowManager.updateViewLayout(flutterView, params);
                        mTrayTimerTask = new TrayAnimationTimerTask();
                        mTrayAnimationTimer = new Timer();
                        mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        }
        return false;
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0 : szWindow.x - flutterView.getWidth();
                    return;
                case "left":
                    mDestX = 0;
                    return;
                case "right":
                    mDestX = szWindow.x - flutterView.getWidth();
                    return;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    break;
            }
        }

        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                if (windowManager != null) {
                    windowManager.updateViewLayout(flutterView, params);
                }
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    TrayAnimationTimerTask.this.cancel();
                    mTrayAnimationTimer.cancel();
                }
            });
        }
    }


}