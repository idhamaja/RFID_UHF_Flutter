import 'dart:async';
import 'package:flutter/services.dart';
import 'package:rfid_03/uhf/uhf_adapter.dart';

class MethodChannelUhfAdapter implements UhfAdapter {
  static const _method = MethodChannel('uhf');
  static const _event = EventChannel('uhf/tags');

  final _ctrl = StreamController<TagHitNative>.broadcast();
  final bool _useEvents;

  StreamSubscription? _eventSub;

  Timer? _pullTimer;
  int _startedAtMs = 0;
  bool _rpcBusy = false;

  Duration _period = const Duration(milliseconds: 12);
  int _lastEventMs = 0;

  MethodChannelUhfAdapter({bool useEvents = true}) : _useEvents = useEvents {
    if (_useEvents) {
      _eventSub = _event.receiveBroadcastStream().listen((e) {
        final now = DateTime.now().millisecondsSinceEpoch;
        if (e is List) {
          for (final it in e) {
            final hit = TagHitNative.fromAny(it);
            if (hit.epc.isNotEmpty && _passRssi(hit.rssi)) _ctrl.add(hit);
          }
        } else {
          final hit = TagHitNative.fromAny(e);
          if (hit.epc.isNotEmpty && _passRssi(hit.rssi)) _ctrl.add(hit);
        }
        _lastEventMs = now;
      }, onError: (_) {});
    }
  }

  @override
  Stream<TagHitNative> get stream => _ctrl.stream;

  // filter RSSI: <4s longgar (>= -75), lalu normal (>= -60)
  bool _passRssi(dynamic r) {
    int val;
    if (r is num)
      val = r.toInt();
    else {
      final p = int.tryParse('$r') ?? -90;
      val = (p > 0 && p <= 300) ? (-90 + (p * 60 ~/ 300)) : p;
    }
    final age = DateTime.now().millisecondsSinceEpoch - _startedAtMs;
    final thr = age < 4000 ? -75 : -60;
    return val >= thr;
  }

  Duration _wantedPeriod() {
    final age = DateTime.now().millisecondsSinceEpoch - _startedAtMs;
    if (age < 1000) return const Duration(milliseconds: 12);
    if (age < 3000) return const Duration(milliseconds: 40);
    return const Duration(milliseconds: 100);
  }

  void _reschedIfNeeded() {
    final want = _wantedPeriod();
    if (_pullTimer == null || want != _period) {
      _pullTimer?.cancel();
      _period = want;
      _pullTimer = Timer.periodic(_period, (_) async => _pullOnce());
    }
  }

  @override
  Future<void> startInventory() async {
    _pullTimer?.cancel();
    _startedAtMs = DateTime.now().millisecondsSinceEpoch;

    _reschedIfNeeded();
    Timer.periodic(const Duration(milliseconds: 200), (t) {
      if (_pullTimer == null) {
        t.cancel();
        return;
      }
      _reschedIfNeeded();
      if (DateTime.now().millisecondsSinceEpoch - _startedAtMs > 3500)
        t.cancel();
    });

    await _method.invokeMethod('startInventory');
  }

  Future<void> _pullOnce() async {
    if (_useEvents) {
      final now = DateTime.now().millisecondsSinceEpoch;
      if (now - _lastEventMs < 120) return; // was 180
    }
    if (_rpcBusy) return;
    _rpcBusy = true;
    try {
      final res = await _method.invokeMethod('pullBatch');
      if (res is List) {
        for (final it in res) {
          final hit = TagHitNative.fromAny(it);
          if (hit.epc.isNotEmpty && _passRssi(hit.rssi)) _ctrl.add(hit);
        }
      } else if (res != null) {
        final hit = TagHitNative.fromAny(res);
        if (hit.epc.isNotEmpty && _passRssi(hit.rssi)) _ctrl.add(hit);
      }
    } finally {
      _rpcBusy = false;
    }
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
