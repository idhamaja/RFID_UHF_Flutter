import 'dart:async';
import 'package:flutter/services.dart';
import 'package:rfid_03/uhf/uhf_adapter.dart';

class MethodChannelUhfAdapter implements UhfAdapter {
  static const _method = MethodChannel('uhf');
  static const _event = EventChannel('uhf/tags');

  final _ctrl = StreamController<TagHitNative>.broadcast();
  StreamSubscription? _eventSub;
  Timer? _pullTimer;
  int _lastEventMs = 0;

  MethodChannelUhfAdapter() {
    _eventSub = _event.receiveBroadcastStream().listen(
      (e) {
        final now = DateTime.now().millisecondsSinceEpoch;
        if (e is List) {
          for (final it in e) {
            final hit = TagHitNative.fromAny(it);
            if (hit.epc.isNotEmpty) _ctrl.add(hit);
          }
        } else {
          final hit = TagHitNative.fromAny(e);
          if (hit.epc.isNotEmpty) _ctrl.add(hit);
        }
        _lastEventMs = now;
      },
      onError: (_) {
        /* polling backup jalan terus */
      },
    );
  }

  @override
  Stream<TagHitNative> get stream => _ctrl.stream;

  @override
  Future<void> startInventory() async {
    // polling backup: agresif tapi ringan (selalu jalan; paket kecil)
    _pullTimer?.cancel();
    _pullTimer = Timer.periodic(const Duration(milliseconds: 45), (_) async {
      final now = DateTime.now().millisecondsSinceEpoch;
      // kalau event lagi lancar, biarin tapi tetap sesekali tarik supaya
      // buffer SDK tak menumpuk (≤ 120ms).
      if (now - _lastEventMs < 120) return;
      try {
        final res = await _method.invokeMethod('pullBatch');
        if (res is List) {
          for (final it in res) {
            final hit = TagHitNative.fromAny(it);
            if (hit.epc.isNotEmpty) _ctrl.add(hit);
          }
        } else if (res != null) {
          final hit = TagHitNative.fromAny(res);
          if (hit.epc.isNotEmpty) _ctrl.add(hit);
        }
      } catch (_) {}
    });

    await _method.invokeMethod('startInventory');
  }

  @override
  Future<void> stopInventory() async {
    _pullTimer?.cancel();
    _pullTimer = null;
    await _method.invokeMethod('stopInventory');
  }

  @override
  Future<void> dispose() async {
    _pullTimer?.cancel();
    await _eventSub?.cancel();
    await _ctrl.close();
  }

  @override
  Future<void> setPower(int dbm) =>
      _method.invokeMethod('setPower', {'power': dbm});

  @override
  Future<void> setBeepEnabled(bool enabled) =>
      _method.invokeMethod('setBeep', {'enabled': enabled});

  @override
  Future<void> setVibrateEnabled(bool enabled) =>
      _method.invokeMethod('setVibrate', {'enabled': enabled});
}
