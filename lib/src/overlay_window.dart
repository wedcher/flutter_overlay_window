import 'dart:async';
import 'dart:developer';

import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/src/models/overlay_position.dart';
import 'package:flutter_overlay_window/src/overlay_config.dart';

class FlutterOverlayWindow {
  FlutterOverlayWindow._();

  static final StreamController _controller = StreamController();
  static const MethodChannel _channel =
      MethodChannel("x-slayer/overlay_channel");
  static const MethodChannel _overlayChannel =
      MethodChannel("x-slayer/overlay");
  static const BasicMessageChannel _overlayMessageChannel =
      BasicMessageChannel("x-slayer/overlay_messenger", JSONMessageCodec());

  /// Open overLay content
  ///
  /// - Optional arguments:
  ///
  /// `height` the overlay height and default is [WindowSize.fullCover]
  ///
  /// `width` the overlay width and default is [WindowSize.matchParent]
  ///
  /// `alignment` the alignment postion on screen and default is [OverlayAlignment.center]
  ///
  /// `visibilitySecret` the detail displayed in notifications on the lock screen and default is [NotificationVisibility.visibilitySecret]
  ///
  /// `OverlayFlag` the overlay flag and default is [OverlayFlag.defaultFlag]
  ///
  /// `overlayTitle` the notification message and default is "overlay activated"
  ///
  /// `overlayContent` the notification message
  ///
  /// `enableDrag` to enable/disable dragging the overlay over the screen and default is "false"
  ///
  /// `positionGravity` the overlay postion after drag and default is [PositionGravity.none]
  ///
  /// `startPosition` the overlay start position and default is null
  static Future<void> showOverlay({
    int height = WindowSize.fullCover,
    int width = WindowSize.matchParent,
    OverlayAlignment alignment = OverlayAlignment.center,
    NotificationVisibility visibility = NotificationVisibility.visibilitySecret,
    OverlayFlag flag = OverlayFlag.defaultFlag,
    String overlayTitle = "overlay activated",
    String? overlayContent,
    bool enableDrag = false,
    PositionGravity positionGravity = PositionGravity.none,
    OverlayPosition? startPosition,
  }) async {
    await _channel.invokeMethod(
      'showOverlay',
      {
        "height": height,
        "width": width,
        "alignment": alignment.name,
        "flag": flag.name,
        "overlayTitle": overlayTitle,
        "overlayContent": overlayContent,
        "enableDrag": enableDrag,
        "notificationVisibility": visibility.name,
        "positionGravity": positionGravity.name,
        "startPosition": startPosition?.toMap(),
      },
    );
  }

  /// Check if overlay permission is granted
  static Future<bool> isPermissionGranted() async {
    try {
      return await _channel.invokeMethod<bool>('checkPermission') ?? false;
    } on PlatformException catch (error) {
      log("$error");
      return Future.value(false);
    }
  }

  /// Request overlay permission
  /// it will open the overlay settings page and return `true` once the permission granted.
  static Future<bool?> requestPermission() async {
    try {
      return await _channel.invokeMethod<bool?>('requestPermission');
    } on PlatformException catch (error) {
      log("Error requestPermession: $error");
      rethrow;
    }
  }

  /// Pikmin fork addition: temporarily hide (fully transparent, not
  /// touchable) or show again the overlay window WITHOUT closing it — e.g.
  /// while the app's own gallery picker is on screen. Callable from the
  /// app's main engine. Returns false when no overlay window exists.
  static Future<bool> setOverlayHidden(bool hidden) async {
    final bool? res = await _channel.invokeMethod<bool?>(
      'setOverlayHidden',
      {'hidden': hidden},
    );
    return res ?? false;
  }

  /// Closes overlay if open
  static Future<bool?> closeOverlay() async {
    final bool? _res = await _channel.invokeMethod('closeOverlay');
    return _res;
  }

  /// Broadcast data to and from overlay app
  static Future shareData(dynamic data) async {
    return await _overlayMessageChannel.send(data);
  }

  /// Streams message shared between overlay and main app
  static Stream<dynamic> get overlayListener {
    _overlayMessageChannel.setMessageHandler((message) async {
      _controller.add(message);
      return message;
    });
    return _controller.stream;
  }

  /// Update the overlay flag while the overlay in action
  static Future<bool?> updateFlag(OverlayFlag flag) async {
    final bool? _res = await _overlayChannel
        .invokeMethod<bool?>('updateFlag', {'flag': flag.name});
    return _res;
  }

  /// Update the overlay size in the screen
  static Future<bool?> resizeOverlay(
    int width,
    int height,
    bool enableDrag,
  ) async {
    final bool? _res = await _overlayChannel.invokeMethod<bool?>(
      'resizeOverlay',
      {
        'width': width,
        'height': height,
        'enableDrag': enableDrag,
      },
    );
    return _res;
  }

  /// **Pikmin fork addition**: sets the rectangles inside which the
  /// overlay window's native `enableDrag` handling should be disabled for
  /// the whole gesture (buttons, a scrollable list, etc.), letting the
  /// touch flow through to Flutter's own gesture handling untouched
  /// instead. Replaces the previous list wholesale — call again with an
  /// empty list to clear all exclusions.
  ///
  /// [rects] must be in **physical px, relative to this overlay window's
  /// own top-left corner** — i.e. exactly what
  /// `RenderBox.localToGlobal(Offset.zero) & renderBox.size` gives you
  /// (in logical px) once multiplied by `devicePixelRatio`. This is
  /// deliberately NOT the same coordinate space as [moveOverlay]/
  /// [getOverlayPosition] (which are dp, relative to the device screen) —
  /// the native side compares against `MotionEvent.getX()/getY()`
  /// (view-relative), not `getRawX()/getRawY()` (screen-absolute), so no
  /// screen-absolute window position needs to be known by either side.
  ///
  /// Only callable from the overlay isolate's own engine (same channel as
  /// [resizeOverlay]/[updateFlag] — calling from the main app isolate has
  /// no registered handler).
  static Future<bool?> setDragExclusionRects(List<Rect> rects) async {
    final bool? _res = await _overlayChannel.invokeMethod<bool?>(
      'setDragExclusionRects',
      {
        'rects': [
          for (final r in rects)
            {
              'left': r.left,
              'top': r.top,
              'right': r.right,
              'bottom': r.bottom,
            },
        ],
      },
    );
    return _res;
  }

  /// **Pikmin fork addition (top/left/right drag bounds)**: sets clamp
  /// limits applied DURING native `enableDrag` dragging (inside
  /// `OverlayService.onTouch()`'s `ACTION_MOVE`, before the position is
  /// applied) — this is a real-time "don't let it cross" clamp, not the
  /// "let it cross, then snap back afterwards" style of correction the
  /// consuming app does today in its own Dart-side position watcher for
  /// the ball. Real-time clamping can only happen here, on the native
  /// side: `enableDrag` gestures are handled entirely inside `onTouch()`
  /// and never surface intermediate `ACTION_MOVE` positions to Dart at
  /// all (only the final position, once, via [setListDragEndedListener]).
  ///
  /// [minYPx]/[minXPx]/[maxXPx] are all optional and independent — pass
  /// only the ones you want clamped. A caller that wants a top clamp but
  /// deliberately allows the window to hang off the left/right edges
  /// (e.g. a wide panel) should pass only [minYPx]. Omitting a param
  /// resets that one axis back to unclamped (native "unset" sentinel),
  /// it does not leave a previous value in place — call again with all
  /// three omitted to fully clear. There is no absolute `maxYPx`; the
  /// bottom edge is clamped only through [safeArea].
  ///
  /// [safeArea] (optional): keep [DragSafeArea.contentPx] inside the
  /// Android system safe area (bars, cutout, mandatory gesture zones) on
  /// the flagged edges. Native reads that area itself (WindowMetrics) and
  /// keeps it fresh on attach / config / insets changes and on every touch
  /// down, so no per-drag Dart round trip is needed. Applied on top of —
  /// never looser than — the absolute bounds. Omitted = rule off.
  ///
  /// Same physical-px, window-relative coordinate space as
  /// [setDragExclusionRects] (multiply logical px by `devicePixelRatio`
  /// yourself before calling). Only callable from the overlay isolate's
  /// own engine, same as [setDragExclusionRects]/[resizeOverlay].
  /// Pikmin fork addition (2026-10-09): drag threshold (physical px) for this
  /// overlay's native drag; `null`/<= 0 restores the system touch slop.
  /// Static native state like [setDragBounds] — reset it when leaving the
  /// view that needed it. Only callable from the overlay isolate's engine.
  static Future<bool?> setDragSlop(int? px) async {
    return _overlayChannel.invokeMethod<bool?>('setDragSlop', {'px': px ?? -1});
  }

  static Future<bool?> setDragBounds({
    double? minYPx,
    double? minXPx,
    double? maxXPx,
    DragSafeArea? safeArea,
  }) async {
    final bool? _res = await _overlayChannel.invokeMethod<bool?>(
      'setDragBounds',
      {
        'bounds': {
          if (minYPx != null) 'minY': minYPx,
          if (minXPx != null) 'minX': minXPx,
          if (maxXPx != null) 'maxX': maxXPx,
          if (safeArea != null) 'safeArea': safeArea.toMap(),
        },
      },
    );
    return _res;
  }

  /// **Pikmin fork addition (dynamic drag safe area)**: the screen-level
  /// safe rectangle (dp, screen coordinates — same space as
  /// [moveOverlay]/[getOverlayPosition]) native uses for [setDragBounds]'
  /// safe-area rule. Callable from the main app isolate. [fallbackTopDp]
  /// is used only when the insets can't be read (Android < 11).
  static Future<SystemSafeRect?> getSystemSafeArea({
    double fallbackTopDp = 0,
  }) async {
    final res = await _channel.invokeMapMethod<String, dynamic>(
      'getSystemSafeArea',
      {'fallbackTopDp': fallbackTopDp},
    );
    if (res == null) return null;
    return SystemSafeRect(
      left: (res['left'] as num).toDouble(),
      top: (res['top'] as num).toDouble(),
      right: (res['right'] as num).toDouble(),
      bottom: (res['bottom'] as num).toDouble(),
      source: res['source'] as String? ?? '',
    );
  }

  /// **Pikmin fork addition (checkpoint 1b — native -> overlay isolate
  /// communication verification)**: registers a listener for the native
  /// `listDragEnded` event, fired from `OverlayService.onTouch()`'s
  /// `ACTION_UP`/`ACTION_CANCEL` handling whenever a real drag (touch
  /// moved past the native drag threshold, not just a tap) just ended
  /// while `enableDrag` was on. [xPx]/[yPx] are the window's final
  /// `WindowManager.LayoutParams.x/y`, in **physical px** (native does no
  /// unit conversion for this event — same reasoning as
  /// [setDragExclusionRects]'s px choice: the caller already needs
  /// `devicePixelRatio` locally for other things, no reason to duplicate
  /// px/dp conversion on the native side too).
  ///
  /// Deliberately does NOT go through `WindowSetup.messenger`/`shareData`
  /// — this reuses the SAME `x-slayer/overlay` channel already used
  /// (Dart-to-native direction) by [resizeOverlay]/[updateFlag]/
  /// [setDragExclusionRects], just in the reverse (native-to-Dart)
  /// direction. Only receives events on the overlay isolate's own engine
  /// (the main app isolate has no handler registered on this channel at
  /// all, so calling this from there would simply never receive anything
  /// — there's nothing to send from the main-isolate side of this
  /// channel).
  ///
  /// Calling this replaces any previously-registered
  /// `_overlayChannel` method call handler wholesale (there is only ever
  /// one handler per channel per engine) — do not also try to register a
  /// separate handler for some other method on this same channel from
  /// app code.
  static void setListDragEndedListener(
    void Function(double xPx, double yPx) listener,
  ) {
    _overlayChannel.setMethodCallHandler((call) async {
      if (call.method == 'listDragEnded') {
        final args = Map<Object?, Object?>.from(call.arguments as Map);
        final x = (args['x'] as num).toDouble();
        final y = (args['y'] as num).toDouble();
        listener(x, y);
      }
      return null;
    });
  }

  /// Update the overlay position in the screen
  ///
  /// `position` the new position of the overlay
  ///
  /// `return` true if the position updated successfully
  static Future<bool?> moveOverlay(OverlayPosition position) async {
    final bool? _res = await _channel.invokeMethod<bool?>(
      'moveOverlay',
      position.toMap(),
    );
    return _res;
  }

  /// Get the current overlay position
  ///
  /// `return` the current overlay position
  static Future<OverlayPosition> getOverlayPosition() async {
    final Map<Object?, Object?>? _res = await _channel.invokeMethod(
      'getOverlayPosition',
    );
    return OverlayPosition.fromMap(_res);
  }

  /// Check if the current overlay is active
  static Future<bool> isActive() async {
    final bool? _res = await _channel.invokeMethod<bool?>('isOverlayActive');
    return _res ?? false;
  }

  /// Dispose overlay stream
  static void disposeOverlayListener() {
    _controller.close();
  }
}

/// **Pikmin fork addition**: rule for [FlutterOverlayWindow.setDragBounds]'
/// `safeArea`. [contentPx] is physical px relative to the overlay window's
/// own top-left (e.g. the ball inside an enlarged window).
class DragSafeArea {
  const DragSafeArea({
    required this.contentPx,
    this.left = false,
    this.top = false,
    this.right = false,
    this.bottom = false,
    this.fallbackTopPx = 0,
  });

  final Rect contentPx;
  final bool left;
  final bool top;
  final bool right;
  final bool bottom;

  /// Top protection used only when native can't read the insets.
  final double fallbackTopPx;

  Map<String, dynamic> toMap() => {
        'contentLeft': contentPx.left,
        'contentTop': contentPx.top,
        'contentRight': contentPx.right,
        'contentBottom': contentPx.bottom,
        'left': left,
        'top': top,
        'right': right,
        'bottom': bottom,
        'fallbackTop': fallbackTopPx,
      };
}

/// **Pikmin fork addition**: result of
/// [FlutterOverlayWindow.getSystemSafeArea] — dp, screen coordinates.
class SystemSafeRect {
  const SystemSafeRect({
    required this.left,
    required this.top,
    required this.right,
    required this.bottom,
    required this.source,
  });

  final double left;
  final double top;
  final double right;
  final double bottom;

  /// `metrics` (WindowMetrics insets) or `fallback`.
  final String source;

  @override
  String toString() => 'SystemSafeRect(l=$left t=$top r=$right b=$bottom '
      'source=$source)';
}
