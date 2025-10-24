import 'dart:async';

class TagHitNative {
  final String epc;
  final int rssi; // dBm
  TagHitNative(this.epc, this.rssi);

  factory TagHitNative.fromAny(dynamic e) {
    if (e == null) return TagHitNative('', -70);

    String? epc;
    int? raw;

    if (e is Map) {
      epc =
          _asString(e['epc']) ??
          _asString(e['EPC']) ??
          _asString(e['Epc']) ??
          _parseEpcFromText(_asString(e['text']) ?? _asString(e['raw']));
      raw =
          _asInt(e['rssiDbm']) ??
          _asInt(e['rssidBm']) ??
          _asInt(e['rssi']) ??
          _asInt(e['RSSI']) ??
          _asInt(e['readRssi']) ??
          _parseRssiFromText(_asString(e['text']) ?? _asString(e['raw']));
    } else if (e is String) {
      epc = _parseEpcFromText(e);
      raw = _parseRssiFromText(e);
    } else {
      epc = e.toString();
      raw = -70;
    }

    raw ??= -70;
    final dbm = (raw > 0 && raw <= 300) ? (-90 + (raw * 60 ~/ 300)) : raw;
    return TagHitNative(epc ?? '', dbm);
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

  /// fullScan=true -> native kumpulkan EPC selama [fullScanMs] lalu kirim 1 batch besar
  Future<void> startInventory({bool fullScan = false, int fullScanMs = 1800});
  Future<void> stopInventory();
  Future<void> dispose();
  Future<void> setPower(int dbm);
  Future<void> setBeepEnabled(bool enabled);
  Future<void> setVibrateEnabled(bool enabled);
}
