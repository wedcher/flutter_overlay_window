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
