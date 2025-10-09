import 'dart:async';

/// 1 hit/tag dari native
class TagHitNative {
  final String epc;
  final int rssi; // dBm
  TagHitNative(this.epc, this.rssi);

  factory TagHitNative.fromAny(dynamic e) {
    if (e == null) return TagHitNative('', -70);

    if (e is Map) {
      String? epc =
          _asString(e['epc']) ??
          _asString(e['EPC']) ??
          _asString(e['Epc']) ??
          _parseEpcFromText(_asString(e['text']) ?? _asString(e['raw']));
      final int rssi =
          _asInt(e['rssi']) ??
          _asInt(e['RSSI']) ??
          _asInt(e['rssiDbm']) ??
          _asInt(e['rssidBm']) ??
          _asInt(e['readRssi']) ??
          _parseRssiFromText(_asString(e['text']) ?? _asString(e['raw'])) ??
          -70;
      return TagHitNative(epc ?? '', rssi);
    }

    if (e is String) {
      final epc = _parseEpcFromText(e) ?? '';
      final rssi = _parseRssiFromText(e) ?? -70;
      return TagHitNative(epc, rssi);
    }

    // fallback
    return TagHitNative(e.toString(), -70);
  }

  static String? _asString(dynamic v) {
    if (v == null) return null;
    final s = v.toString().trim();
    return s.isEmpty ? null : s;
  }

  static int? _asInt(dynamic v) {
    if (v == null) return null;
    if (v is int) return v;
    if (v is double) return v.round();
    return int.tryParse('$v');
  }

  static String? _parseEpcFromText(String? s) {
    if (s == null) return null;
    final ms = RegExp(r'([A-Fa-f0-9]{20,})').allMatches(s).toList();
    if (ms.isEmpty) return null;
    ms.sort((a, b) => b.group(0)!.length.compareTo(a.group(0)!.length));
    return ms.first.group(0)!.toUpperCase();
  }

  static int? _parseRssiFromText(String? s) {
    if (s == null) return null;
    final m = RegExp(
      r'(-?\d{1,3})\s*d?B?m?',
      caseSensitive: false,
    ).firstMatch(s);
    return m == null ? null : int.tryParse(m.group(1)!);
  }
}

abstract class UhfAdapter {
  Stream<TagHitNative> get stream;
  Future<void> startInventory();
  Future<void> stopInventory();
  Future<void> dispose();
  Future<void> setPower(int dbm);
  Future<void> setBeepEnabled(bool enabled);
  Future<void> setVibrateEnabled(bool enabled);
}
