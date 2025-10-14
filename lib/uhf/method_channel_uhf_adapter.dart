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

  // Timer tidak expose periode, simpan sendiri
  Duration _period = const Duration(milliseconds: 20); // super cepat di awal
  int _lastEventMs = 0;

  MethodChannelUhfAdapter({bool useEvents = true}) : _useEvents = useEvents {
    if (_useEvents) {
      _eventSub = _event.receiveBroadcastStream().listen((e) {
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
      }, onError: (_) {});
    }
  }

  @override
  Stream<TagHitNative> get stream => _ctrl.stream;

  Duration _wantedPeriod() {
    final now = DateTime.now().millisecondsSinceEpoch;
    final age = now - _startedAtMs;
    if (age < 1200)
      return const Duration(milliseconds: 20); // 1.2s pertama agresif
    if (age < 4000) return const Duration(milliseconds: 60); // stabilisasi
    return const Duration(milliseconds: 120); // hemat
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

    // Start polling backup (EventChannel tetap jadi jalur utama)
    _reschedIfNeeded();
    // Re-evaluate interval beberapa kali di awal supaya selalu optimal
    Timer.periodic(const Duration(milliseconds: 200), (t) {
      if (_pullTimer == null) {
        t.cancel();
        return;
      }
      _reschedIfNeeded();
      if (DateTime.now().millisecondsSinceEpoch - _startedAtMs > 4500)
        t.cancel();
    });

    await _method.invokeMethod('startInventory');
  }

  Future<void> _pullOnce() async {
    // Jika event deras (<180ms), skip polling agar tidak membebani IPC
    if (_useEvents) {
      final now = DateTime.now().millisecondsSinceEpoch;
      if (now - _lastEventMs < 180) return;
    }
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
      // biarkan, native tetap jalan
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
