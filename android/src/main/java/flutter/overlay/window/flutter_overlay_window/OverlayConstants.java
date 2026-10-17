package flutter.overlay.window.flutter_overlay_window;

final public class OverlayConstants {

    static final String CACHED_TAG = "myCachedEngine";
    static final String CHANNEL_TAG = "x-slayer/overlay_channel";
    static final String OVERLAY_TAG = "x-slayer/overlay";
    static final String MESSENGER_TAG = "x-slayer/overlay_messenger";
    // Pikmin fork change (B73): the ongoing service notification uses a quiet
    // IMPORTANCE_LOW channel. A channel's importance cannot be changed after
    // creation, so it needs a new id; the old one is deleted on start.
    static final String CHANNEL_ID = "overlay_service_quiet";
    static final String LEGACY_CHANNEL_ID = "Overlay Channel";
    static final int NOTIFICATION_ID = 4579;
    static final int DEFAULT_XY = -6;
}
