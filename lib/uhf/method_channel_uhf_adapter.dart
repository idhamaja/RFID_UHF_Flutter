import 'dart:async';
import 'package:flutter/services.dart';
import 'package:rfid_03/uhf/uhf_adapter.dart';

class MethodChannelUhfAdapter implements UhfAdapter {
  static const _method = MethodChannel('uhf');
  static const _event = EventChannel('uhf/tags');

  final _ctrl = StreamController<TagHitNative>.broadcast();
  StreamSubscription? _eventSub;

  Timer? _pullTimer;
  int _startedAtMs = 0;
  bool _rpcBusy = false;

  // simpan interval karena Timer tidak expose "period"
  Duration _lastPeriod = const Duration(milliseconds: 30);

  MethodChannelUhfAdapter({bool useEvents = false}) {
    if (useEvents) {
      _eventSub = _event.receiveBroadcastStream().listen((e) {
        if (e is List) {
          for (final it in e) {
            final hit = TagHitNative.fromAny(it);
            if (hit.epc.isNotEmpty) _ctrl.add(hit);
          }
        } else {
          final hit = TagHitNative.fromAny(e);
          if (hit.epc.isNotEmpty) _ctrl.add(hit);
        }
      }, onError: (_) {});
    }
  }

  @override
  Stream<TagHitNative> get stream => _ctrl.stream;

  Duration _wantedPeriod() {
    final now = DateTime.now().millisecondsSinceEpoch;
    return (now - _startedAtMs) < 1200
        ? const Duration(milliseconds: 30) // fase cepat
        : const Duration(milliseconds: 90); // sustain
  }

  void _startOrRescheduleTimer() {
    final want = _wantedPeriod();
    if (_pullTimer == null) {
      _lastPeriod = want;
      _pullTimer = Timer.periodic(_lastPeriod, (_) async => _pullOnce());
      return;
    }
    if (want != _lastPeriod) {
      _pullTimer!.cancel();
      _lastPeriod = want;
      _pullTimer = Timer.periodic(_lastPeriod, (_) async => _pullOnce());
    }
  }

  @override
  Future<void> startInventory() async {
    _pullTimer?.cancel();
    _startedAtMs = DateTime.now().millisecondsSinceEpoch;

    // mulai polling backup (event channel sudah aktif jika dipilih)
    _startOrRescheduleTimer();

    // start di native
    await _method.invokeMethod('startInventory');

    // cek periodik untuk berpindah interval setelah 1.2s
    Timer.periodic(const Duration(milliseconds: 100), (tick) {
      if (_pullTimer == null) {
        tick.cancel();
        return;
      }
      _startOrRescheduleTimer();
      if (_wantedPeriod() == const Duration(milliseconds: 90)) {
        tick.cancel();
      }
    });
  }

  Future<void> _pullOnce() async {
    if (_rpcBusy) return;
    _rpcBusy = true;
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
    } catch (_) {
      // biarkan – native tetap jalan
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
