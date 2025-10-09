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

  final Map<String, TagRow> _tagRows = {};
  final Map<String, _PendingAgg> _pending = {};

  List<TagRow> _sortedRowsCache = [];
  bool _isCacheDirty = true;

  int _totalHitCount = 0;
  DateTime? _startTime;

  Timer? _flushUiTimer;

  List<TagRow> get rows {
    if (_isCacheDirty) {
      _sortedRowsCache = _tagRows.values.toList()
        ..sort((a, b) => b.lastSeen.compareTo(a.lastSeen));
      _isCacheDirty = false;
    }
    return _sortedRowsCache;
  }

  int get uniqueTagCount => _tagRows.length;
  int get totalHitCount => _totalHitCount;
  int get elapsedMilliseconds => _startTime == null
      ? 0
      : DateTime.now().difference(_startTime!).inMilliseconds;

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

    // jadwalkan flush UI (coalesced)
    _flushUiTimer ??= Timer(_flushEvery, _applyPending);
  }

  void _applyPending() {
    _flushUiTimer?.cancel();
    _flushUiTimer = null;

    if (_pending.isEmpty) return;
    _pending.forEach((epc, agg) {
      final row = _tagRows.putIfAbsent(
        epc,
        () =>
            TagRow(epc: epc, count: 0, lastRssi: agg.rssi, lastSeen: agg.last),
      );
      row.count += agg.cnt;
      row.lastRssi = agg.rssi;
      row.lastSeen = agg.last;
      _totalHitCount += agg.cnt;
    });
    _pending.clear();

    _isCacheDirty = true;
    notifyListeners();
  }

  static const _flushEvery = Duration(milliseconds: 12); // ~80 fps

  Future<void> start() async {
    if (_isRunning) return;
    _isRunning = true;
    _startTime ??= DateTime.now();
    notifyListeners();
    try {
      await adapter.setPower(30); // 28–30 dBm kalau modul sanggup
      await adapter.startInventory();
    } catch (e) {
      _isRunning = false;
      notifyListeners();
    }
  }

  Future<void> stop() async {
    if (!_isRunning) return;
    _isRunning = false;
    _flushUiTimer?.cancel();
    _flushUiTimer = null;
    await adapter.stopInventory();
    notifyListeners();
  }

  Future<void> setBeepEnabled(bool enabled) => adapter.setBeepEnabled(enabled);
  Future<void> setVibrateEnabled(bool enabled) =>
      adapter.setVibrateEnabled(enabled);

  void clear() {
    _tagRows.clear();
    _pending.clear();
    _sortedRowsCache = [];
    _totalHitCount = 0;
    _startTime = null;
    _isCacheDirty = true;
    notifyListeners();
  }

  @override
  void dispose() {
    _flushUiTimer?.cancel();
    _sub?.cancel();
    adapter.dispose();
    super.dispose();
  }
}
