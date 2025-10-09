import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:rfid_03/uhf/uhf_adapter.dart';

class TagRow {
  final String epc;
  int count;
  int lastRssi;
  DateTime lastSeen;
  TagRow({
    required this.epc,
    required this.count,
    required this.lastRssi,
    required this.lastSeen,
  });
}

class _PendingAgg {
  int cnt;
  int rssi;
  DateTime last;
  _PendingAgg({required this.cnt, required this.rssi, required this.last});
}

class InventoryController extends ChangeNotifier {
  final UhfAdapter adapter;
  StreamSubscription? _sub;

  InventoryController(this.adapter) {
    _sub = adapter.stream.listen(_onTagHit);
  }

  bool _isRunning = false;
  bool get isRunning => _isRunning;

  final Map<String, TagRow> _rows = {};
  final Map<String, _PendingAgg> _pending = {};
  List<TagRow> _sorted = [];
  bool _dirty = true;

  int _totalHits = 0;
  DateTime? _startAt;
  Timer? _flushTimer;

  List<TagRow> get rows {
    if (_dirty) {
      _sorted = _rows.values.toList()
        ..sort((a, b) => b.lastSeen.compareTo(a.lastSeen));
      _dirty = false;
    }
    return _sorted;
  }

  int get uniqueTagCount => _rows.length;
  int get totalHitCount => _totalHits;
  int get elapsedMilliseconds => _startAt == null
      ? 0
      : DateTime.now().difference(_startAt!).inMilliseconds;

  void _onTagHit(TagHitNative hit) {
    if (!_isRunning || hit.epc.isEmpty) return;
    final now = DateTime.now();
    final agg = _pending.putIfAbsent(
      hit.epc,
      () => _PendingAgg(cnt: 0, rssi: hit.rssi, last: now),
    );
    agg.cnt++;
    agg.rssi = hit.rssi;
    agg.last = now;

    _flushTimer ??= Timer(
      const Duration(milliseconds: 10),
      _applyPending,
    ); // ~100 fps UI
  }

  void _applyPending() {
    _flushTimer?.cancel();
    _flushTimer = null;
    if (_pending.isEmpty) return;

    _pending.forEach((epc, agg) {
      final row = _rows.putIfAbsent(
        epc,
        () =>
            TagRow(epc: epc, count: 0, lastRssi: agg.rssi, lastSeen: agg.last),
      );
      row.count += agg.cnt;
      row.lastRssi = agg.rssi;
      row.lastSeen = agg.last;
      _totalHits += agg.cnt;
    });
    _pending.clear();
    _dirty = true;
    notifyListeners();
  }

  Future<void> start() async {
    if (_isRunning) return;
    _isRunning = true;
    _startAt ??= DateTime.now();
    notifyListeners(); // UI langsung berubah

    try {
      await adapter.setPower(30); // max kalau modul sanggup
      await adapter.startInventory();
    } catch (_) {
      _isRunning = false;
      notifyListeners();
    }
  }

  Future<void> stop() async {
    if (!_isRunning) return;
    _isRunning = false;
    _flushTimer?.cancel();
    _flushTimer = null;
    await adapter.stopInventory();
    notifyListeners();
  }

  Future<void> setBeepEnabled(bool v) => adapter.setBeepEnabled(v);
  Future<void> setVibrateEnabled(bool v) => adapter.setVibrateEnabled(v);

  void clear() {
    _rows.clear();
    _pending.clear();
    _sorted.clear();
    _totalHits = 0;
    _startAt = null;
    _dirty = true;
    notifyListeners();
  }

  @override
  void dispose() {
    _flushTimer?.cancel();
    _sub?.cancel();
    adapter.dispose();
    super.dispose();
  }
}
