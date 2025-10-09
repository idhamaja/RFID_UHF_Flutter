package com.example.rfid_03;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity {

  private static final String METHOD_CH = "uhf";
  private static final String EVENT_CH = "uhf/tags";
  private static final String TAG = "UHF";

  // ==== Tuning anti-ANR / backpressure ====
  private static final int CACHE_LIMIT = 4000; // batasi buffer event
  private static final int PUSH_GAP_MS = 30; // kirim batch tiap ~33 fps
  private static final int PUSH_CHUNK = 64; // max item per batch
  private static final int READER_IDLE_MS = 6; // tidur singkat saat idle

  private static final int BEEP_GAP_MS = 150; // throttle beep
  private static final int VIB_GAP_MS = 180; // throttle vibrate

  private final Handler main = new Handler(Looper.getMainLooper());
  private EventChannel.EventSink sink;

  private boolean pushPosted = false;
  private boolean isBeepEnabled = true;
  private boolean isVibrateEnabled = true;

  private Object uhfMgr;
  private Object uhfFunc;
  private Object gClient;

  private final List<Map<String, Object>> tagCache = new ArrayList<>();

  private volatile boolean powered = false;
  private volatile boolean opened = false;
  private volatile boolean running = false;

  private Thread readerThread;
  private boolean methodsDumped = false;

  // Beep/vibrate reuse + throttle
  private ToneGenerator toneGen;
  private long lastBeepAt = 0L;
  private long lastVibrateAt = 0L;
  private long lastPushAt = 0L;

  @Override
  public void configureFlutterEngine(@NonNull FlutterEngine engine) {
    super.configureFlutterEngine(engine);

    new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), METHOD_CH)
        .setMethodCallHandler((call, result) -> {
          try {
            switch (call.method) {
              case "startInventory":
                startInventory();
                result.success(null);
                break;

              case "stopInventory":
                stopInventory();
                result.success(null);
                break;

              case "pullBatch": { // polling fallback ringan
                List<Map<String, Object>> out = readBatchOnce();
                out.addAll(drainTagCache());
                if (!out.isEmpty()) {
                  safeBeep();
                  safeVibrate(20);
                }
                result.success(out);
                break;
              }

              case "pullOne": {
                Map<String, Object> one = pullOneTagOnce();
                if (one != null) {
                  safeBeep();
                  safeVibrate(20);
                }
                result.success(one);
                break;
              }

              case "setPower": {
                Integer p = call.argument("power");
                setPower(p == null ? 26 : Math.max(5, Math.min(30, p)));
                result.success(null);
                break;
              }

              case "setBeep": {
                Boolean b = call.argument("enabled");
                isBeepEnabled = b != null && b;
                result.success(null);
                break;
              }

              case "setVibrate": {
                Boolean v = call.argument("enabled");
                isVibrateEnabled = v != null && v;
                result.success(null);
                break;
              }

              case "ping":
                result.success("pong:" + getPackageName());
                break;

              default:
                result.notImplemented();
            }
          } catch (Throwable t) {
            Log.e(TAG, "method error: " + call.method, t);
            result.error("UHF_ERR", t.getMessage(), null);
          }
        });

    new EventChannel(engine.getDartExecutor().getBinaryMessenger(), EVENT_CH)
        .setStreamHandler(new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object args, EventChannel.EventSink eventSink) {
            sink = eventSink;
            Log.d(TAG, "EventChannel listener attached");
          }

          @Override
          public void onCancel(Object args) {
            sink = null;
          }
        });
  }

  // --------- Beep / Vibrate (reused + throttled) ----------
  private void safeBeep() {
    if (!isBeepEnabled)
      return;
    long now = SystemClock.uptimeMillis();
    if (now - lastBeepAt < BEEP_GAP_MS)
      return;
    lastBeepAt = now;
    try {
      if (toneGen == null)
        toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
      toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 80);
    } catch (Throwable e) {
      Log.w(TAG, "Beep error", e);
    }
  }

  private void safeVibrate(long millis) {
    if (!isVibrateEnabled)
      return;
    long now = SystemClock.uptimeMillis();
    if (now - lastVibrateAt < VIB_GAP_MS)
      return;
    lastVibrateAt = now;
    try {
      Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
      if (vib == null)
        return;
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        vib.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
      } else {
        // noinspection deprecation
        vib.vibrate(millis);
      }
    } catch (Throwable e) {
      Log.w(TAG, "Vibrate error", e);
    }
  }

  // ---------------- Control ----------------
  private void startReaderLoop() {
    if (readerThread != null)
      return;
    readerThread = new Thread(() -> {
      Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
      while (running) {
        try {
          // beberapa SDK perlu dipicu agar buffer/log terisi
          tryCall(uhfFunc, "GetRFIDInventoryBankDataTogether");
          tryCall(uhfMgr, "GetRFIDInventoryBankDataTogether");
          tryCall(gClient, "GetRFIDInventoryBankDataTogether");

          int drained = drainAllTagsOnce();
          if (drained == 0)
            Thread.sleep(READER_IDLE_MS);
        } catch (InterruptedException ie) {
          break;
        } catch (Throwable t) {
          Log.e(TAG, "reader loop error", t);
          try {
            Thread.sleep(20);
          } catch (InterruptedException e) {
            break;
          }
        }
      }
    }, "uhf-reader");
    readerThread.start();
  }

  private void startInventory() throws Exception {
    ensureReady();

    if (!methodsDumped) {
      dumpAllMethods("uhfMgr", uhfMgr);
      dumpAllMethods("uhfFunc", uhfFunc);
      dumpAllMethods("gClient", gClient);
      methodsDumped = true;
    }

    tryRegisterCallbacks();
    tuneForSpeed();

    if (!invokeAny(uhfFunc, "startInventoryTag")
        && !invokeAny(uhfFunc, "inventoryStart")
        && !invokeAny(uhfFunc, "startRead")
        && !invokeAny(uhfMgr, "startInventoryTag")
        && !invokeAny(uhfMgr, "inventoryStart")
        && !invokeAny(uhfMgr, "startRead")) {
      Log.w(TAG, "No startInventory method found");
    } else {
      Log.d(TAG, "Inventory started");
    }

    running = true;
    startReaderLoop();
  }

  private void stopInventory() {
    running = false;
    if (readerThread != null) {
      readerThread.interrupt();
      readerThread = null;
    }
    try {
      if (!invokeAny(uhfFunc, "stopInventory")
          && !invokeAny(uhfFunc, "inventoryStop")
          && !invokeAny(uhfFunc, "stopRead")
          && !invokeAny(uhfMgr, "stopInventory")
          && !invokeAny(uhfMgr, "inventoryStop")
          && !invokeAny(uhfMgr, "stopRead")) {
        Log.w(TAG, "No stopInventory method found");
      } else {
        Log.d(TAG, "Inventory stopped");
      }
    } catch (Throwable t) {
      Log.e(TAG, "stopInventory error", t);
    }
  }

  private void setPower(int dbm) {
    try {
      if (!invokeAny(uhfFunc, "setReadWritePower", dbm, dbm)
          && !invokeAny(uhfFunc, "powerSet", dbm)
          && !invokeAny(uhfMgr, "setReadWritePower", dbm, dbm)
          && !invokeAny(uhfMgr, "powerSet", dbm)) {
        Log.w(TAG, "No power setter found");
      } else {
        Log.d(TAG, "Power set " + dbm + " dBm");
      }
    } catch (Throwable t) {
      Log.e(TAG, "setPower error", t);
    }
  }

  private void ensureReady() throws Exception {
    if (uhfMgr == null)
      initManagerFunction();
    if (!powered)
      powerOn();
    if (!opened)
      openSerialIfAny();
  }

  // ---------------- INIT via reflection ----------------
  private void initManagerFunction() throws Exception {
    Class<?> mgrClz = Class.forName("com.uhf.base.UHFManager");
    Method getter = null;
    for (Method mm : mgrClz.getDeclaredMethods()) {
      if (mm.getName().equals("getUHFImplSigleInstance")) {
        getter = mm;
        break;
      }
    }
    if (getter == null)
      throw new IllegalStateException("UHFManager.getUHFImplSigleInstance not found");
    getter.setAccessible(true);

    Object arg = null;
    if (getter.getParameterCount() == 1) {
      Class<?> p0 = getter.getParameterTypes()[0];
      if (android.content.Context.class.isAssignableFrom(p0))
        arg = getApplicationContext();
      else if (p0.isEnum()) {
        Object[] cons = p0.getEnumConstants();
        arg = (cons != null && cons.length > 0) ? cons[0] : null;
      }
    }
    uhfMgr = (getter.getParameterCount() == 0) ? getter.invoke(null) : getter.invoke(null, arg);

    uhfFunc = tryGetField(uhfMgr, "mUhfFunction");
    if (uhfFunc == null)
      uhfFunc = tryGetField(uhfMgr, "mFunction");
    if (uhfFunc == null)
      uhfFunc = tryCall(uhfMgr, "getFunction");
    if (uhfFunc == null)
      uhfFunc = tryCall(uhfMgr, "getUhfFunction");
    if (uhfFunc == null) {
      try {
        Class<?> fn = Class.forName("com.uhf.base.UHFFunction");
        for (Method m : fn.getDeclaredMethods()) {
          if (m.getName().equals("getInstance")) {
            m.setAccessible(true);
            uhfFunc = (m.getParameterCount() == 1) ? m.invoke(null, getApplicationContext()) : m.invoke(null);
            break;
          }
        }
      } catch (Throwable ignore) {
      }
    }
    Log.d(TAG, "init: mgr=" + (uhfMgr != null) + ", func=" + (uhfFunc != null));
  }

  private void powerOn() {
    try {
      if (!invokeAny(uhfMgr, "setPowerState_UHF", true))
        Log.w(TAG, "setPowerState_UHF not found");
      if (!invokeAny(uhfMgr, "enableUartComm_UHF", true))
        Log.w(TAG, "enableUartComm_UHF not found");
      invokeAny(uhfMgr, "powerOn");
      invokeAny(uhfFunc, "powerOn");
      powered = true;
      setPower(26);
      Log.d(TAG, "Module power ON");
    } catch (Throwable t) {
      Log.e(TAG, "powerOn error", t);
    }
  }

  private void openSerialIfAny() {
    try {
      Class<?> gClz = Class.forName("com.idata.gg.reader.api.dal.GClient");
      try {
        Method gi = gClz.getDeclaredMethod("getInstance");
        gi.setAccessible(true);
        gClient = gi.invoke(null);
      } catch (NoSuchMethodException e) {
        Constructor<?> c = gClz.getDeclaredConstructor();
        c.setAccessible(true);
        gClient = c.newInstance();
      }

      boolean ok = false;
      try {
        // prefer signature (String,int)
        Method open2 = gClz.getMethod("openAndroidSerial", String.class, int.class);
        String[] nodes = new String[] { "/dev/ttyS4", "/dev/ttyS3", "/dev/ttyHSL0", "/dev/ttyMT2" };
        for (String n : nodes) {
          try {
            open2.invoke(gClient, n, 115200);
            ok = true;
            Log.d(TAG, "openAndroidSerial(" + n + ") OK");
            break;
          } catch (Throwable ignore) {
          }
        }
      } catch (NoSuchMethodException ignore) {
        try {
          Method open1 = gClz.getMethod("openAndroidSerial", int.class);
          open1.invoke(gClient, 115200);
          ok = true;
          Log.d(TAG, "openAndroidSerial(115200) OK");
        } catch (NoSuchMethodException e2) {
          Log.w(TAG, "GClient.openAndroidSerial not found");
        }
      }
      opened = ok || true;
    } catch (Throwable t) {
      Log.w(TAG, "GClient not available: " + t.getMessage());
      opened = true;
    }
  }

  // ---------------- Listener (jika ada) ----------------
  private void tryRegisterCallbacks() {
    try {
      Class<?> logIface = Class.forName("com.idata.gg.reader.api.dal.HandlerTagEpcLog");
      Object logProxy = Proxy.newProxyInstance(
          logIface.getClassLoader(), new Class[] { logIface },
          (proxy, method, args) -> {
            if ("log".equals(method.getName()) && args != null && args.length >= 1) {
              Object info = args[args.length - 1];
              publishTagFromInfo(info);
            }
            return null;
          });

      Class<?> overIface = Class.forName("com.idata.gg.reader.api.dal.HandlerTagEpcOver");
      Object overProxy = Proxy.newProxyInstance(
          overIface.getClassLoader(), new Class[] { overIface },
          (proxy, method, args) -> null);

      boolean ok = registerListenerOnHost(gClient, "onTagEpcLog", logIface, logProxy)
          || registerListenerOnHost(uhfMgr, "onTagEpcLog", logIface, logProxy)
          || registerListenerOnHost(uhfFunc, "onTagEpcLog", logIface, logProxy)
          || registerListenerOnHost(gClient, "setOnTagEpcLog", logIface, logProxy)
          || registerListenerOnHost(gClient, "addTagEpcLogListener", logIface, logProxy);

      registerListenerOnHost(gClient, "onTagEpcOver", overIface, overProxy);
      registerListenerOnHost(uhfMgr, "onTagEpcOver", overIface, overProxy);
      registerListenerOnHost(uhfFunc, "onTagEpcOver", overIface, overProxy);

      Log.d(TAG, "callback EPC " + (ok ? "registered" : "not found"));
    } catch (Throwable t) {
      Log.w(TAG, "callback register skipped: " + t.getMessage());
    }
  }

  private boolean registerListenerOnHost(Object host, String methodName, Class<?> iface, Object proxy) {
    if (host == null)
      return false;
    try {
      for (Method m : host.getClass().getMethods()) {
        if (!m.getName().equals(methodName))
          continue;
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length == 1 && ps[0].isAssignableFrom(iface)) {
          m.setAccessible(true);
          m.invoke(host, proxy);
          return true;
        }
      }
    } catch (Throwable ignored) {
    }
    return false;
  }

  // ------ Batch push ke Flutter (anti-ANR) ------
  private void schedulePush() {
    if (sink == null || pushPosted)
      return;
    pushPosted = true;
    long delay = Math.max(0, PUSH_GAP_MS - (SystemClock.uptimeMillis() - lastPushAt));
    main.postDelayed(() -> {
      pushPosted = false;
      if (sink == null)
        return;

      List<Map<String, Object>> batch = new ArrayList<>();
      synchronized (tagCache) {
        int n = Math.min(PUSH_CHUNK, tagCache.size());
        if (n > 0) {
          batch.addAll(tagCache.subList(0, n));
          tagCache.subList(0, n).clear();
        }
      }
      if (!batch.isEmpty()) {
        lastPushAt = SystemClock.uptimeMillis();
        try {
          sink.success(batch);
        } catch (Throwable t) {
          Log.w(TAG, "push batch error", t);
        }
        safeBeep();
        safeVibrate(12);
        // masih ada sisa? jadwalkan lagi
        if (!tagCache.isEmpty())
          schedulePush();
      }
    }, delay);
  }

  // ---------------- Batch read (pullBatch) ----------------
  private List<Map<String, Object>> readBatchOnce() {
    List<Map<String, Object>> out = new ArrayList<>();

    drainToListBySinglePop(uhfFunc, out);
    drainToListBySinglePop(uhfMgr, out);

    drainToListByList(uhfFunc, out);
    drainToListByList(uhfMgr, out);

    if (out.isEmpty()) { // hanya kalau jalur cepat kosong
      drainToListByTextLog(uhfFunc, out);
      drainToListByTextLog(uhfMgr, out);
      drainToListByTextLog(gClient, out);
    }

    if (out.isEmpty()) {
      drainToListByRawBuffer(uhfFunc, out);
      drainToListByRawBuffer(uhfMgr, out);
      drainToListByRawBuffer(gClient, out);
    }

    if (out.isEmpty()) {
      bruteForceDrain(uhfFunc, out);
      bruteForceDrain(uhfMgr, out);
      bruteForceDrain(gClient, out);
    }
    return out;
  }

  private void drainToListBySinglePop(Object host, List<Map<String, Object>> out) {
    if (host == null)
      return;
    String[] names = new String[] {
        "readTagFromBuffer", "getTagFromBuffer", "popTagFromBuffer",
        "inventoryReadTagFromBuffer", "readBufferTag", "getOneTag",
        "readUhfBufferTag", "readTagFromBufferByOnce", "getEpcFromBuffer"
    };
    for (String n : names) {
      Object tag;
      while ((tag = tryCall(host, n)) != null) {
        Map<String, Object> m = mapFromInfo(tag);
        if (m != null)
          out.add(m);
      }
    }
  }

  private void drainToListByList(Object host, List<Map<String, Object>> out) {
    if (host == null)
      return;
    String[] names = new String[] {
        "getTagList", "getTags", "readBuffer", "getInventoryTagList",
        "getInventoryTag", "inventoryBuffer"
    };
    for (String n : names) {
      Object list = tryCall(host, n);
      if (list == null)
        continue;
      if (list instanceof List) {
        for (Object tag : (List<?>) list) {
          Map<String, Object> m = mapFromInfo(tag);
          if (m != null)
            out.add(m);
        }
      } else if (list.getClass().isArray()) {
        int len = java.lang.reflect.Array.getLength(list);
        for (int i = 0; i < len; i++) {
          Map<String, Object> m = mapFromInfo(java.lang.reflect.Array.get(list, i));
          if (m != null)
            out.add(m);
        }
      }
    }
  }

  // Ambil SEMUA EPC yang muncul di string log
  private void drainToListByTextLog(Object host, List<Map<String, Object>> out) {
    if (host == null)
      return;
    String[] names = new String[] {
        "readEpcLog", "readTagText", "readLog", "getTagEpcLog",
        "getEpcTxt", "getLogString", "getLog", "GetRFIDInventoryBankDataTogether"
    };
    for (String n : names) {
      Object v = tryCall(host, n);
      if (v instanceof String)
        out.addAll(mapsFromTextAll((String) v));
    }
  }

  private List<Map<String, Object>> mapsFromTextAll(String s) {
    List<Map<String, Object>> list = new ArrayList<>();
    if (s == null || s.isEmpty())
      return list;
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("([A-Fa-f0-9]{20,})")
        .matcher(s);
    Integer rssi = parseRssiFromText(s);
    while (m.find()) {
      String epc = m.group(1).toUpperCase();
      Map<String, Object> map = new HashMap<>();
      map.put("epc", epc);
      map.put("rssi", rssi == null ? -70 : rssi);
      list.add(map);
    }
    return list;
  }

  private void drainToListByRawBuffer(Object host, List<Map<String, Object>> out) {
    if (host == null)
      return;
    String[] names = new String[] { "readBuffer", "getBuffer", "getReadBuf", "readTagBuffer", "getInventoryBuffer" };
    for (String n : names) {
      Object v = tryCall(host, n);
      if (v == null)
        continue;

      byte[] buf = null;
      if (v instanceof byte[])
        buf = (byte[]) v;
      else if (v instanceof List) {
        List<?> L = (List<?>) v;
        buf = new byte[L.size()];
        for (int i = 0; i < L.size(); i++)
          buf[i] = ((Number) L.get(i)).byteValue();
      }
      if (buf == null || buf.length == 0)
        continue;

      String text = new String(buf);
      out.addAll(mapsFromTextAll(text));
    }
  }

  // Brute-force terakhir (mahal)
  private void bruteForceDrain(Object host, List<Map<String, Object>> out) {
    if (host == null)
      return;
    for (Method m : host.getClass().getMethods()) {
      try {
        if (m.getParameterTypes().length != 0)
          continue;
        String mn = m.getName().toLowerCase();
        if (!(mn.contains("tag") || mn.contains("epc") || mn.contains("buf") || mn.contains("log")))
          continue;

        Object v = m.invoke(host);
        if (v == null)
          continue;

        if (v instanceof String) {
          out.addAll(mapsFromTextAll((String) v));
          continue;
        }
        if (v instanceof List) {
          for (Object item : (List<?>) v) {
            Map<String, Object> map = mapFromInfo(item);
            if (map != null)
              out.add(map);
          }
          continue;
        }
        if (v.getClass().isArray()) {
          int len = java.lang.reflect.Array.getLength(v);
          for (int i = 0; i < len; i++) {
            Map<String, Object> map = mapFromInfo(java.lang.reflect.Array.get(v, i));
            if (map != null)
              out.add(map);
          }
          continue;
        }
        Map<String, Object> map = mapFromInfo(v);
        if (map != null)
          out.add(map);
      } catch (Throwable ignore) {
      }
    }
  }

  // ---------------- Event push loop ----------------
  private int drainAllTagsOnce() {
    int c = 0;
    c += drainBySinglePop(uhfFunc);
    c += drainBySinglePop(uhfMgr);
    c += drainByList(uhfFunc);
    c += drainByList(uhfMgr);
    return c;
  }

  private int drainBySinglePop(Object host) {
    if (host == null)
      return 0;
    String[] names = new String[] {
        "readTagFromBuffer", "getTagFromBuffer", "popTagFromBuffer",
        "inventoryReadTagFromBuffer", "readBufferTag", "getOneTag",
        "readUhfBufferTag", "readTagFromBufferByOnce", "getEpcFromBuffer"
    };
    int c = 0;
    for (String n : names) {
      Object tag;
      while ((tag = tryCall(host, n)) != null) {
        publishTagFromInfo(tag);
        c++;
      }
    }
    return c;
  }

  private int drainByList(Object host) {
    if (host == null)
      return 0;
    String[] names = new String[] {
        "getTagList", "getTags", "readBuffer", "getInventoryTagList",
        "getInventoryTag", "inventoryBuffer"
    };
    int c = 0;
    for (String n : names) {
      Object list = tryCall(host, n);
      if (list == null)
        continue;
      if (list instanceof List) {
        for (Object tag : (List<?>) list) {
          publishTagFromInfo(tag);
          c++;
        }
      } else if (list.getClass().isArray()) {
        int len = java.lang.reflect.Array.getLength(list);
        for (int i = 0; i < len; i++) {
          publishTagFromInfo(java.lang.reflect.Array.get(list, i));
          c++;
        }
      }
    }
    return c;
  }

  // ---------------- Mapping helpers ----------------
  private static String parseHexFromText(String s) {
    if (s == null)
      return null;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Fa-f0-9]{20,})").matcher(s);
    String best = null;
    while (m.find()) {
      String g = m.group(1);
      if (best == null || g.length() > best.length())
        best = g;
    }
    return best == null ? null : best.toUpperCase();
  }

  private static Integer parseRssiFromText(String s) {
    if (s == null)
      return null;
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("(-?\\d{1,3})\\s*d?B?m?", java.util.regex.Pattern.CASE_INSENSITIVE)
        .matcher(s);
    return m.find() ? Integer.valueOf(m.group(1)) : null;
  }

  private Map<String, Object> mapFromInfo(Object info) {
    if (info == null)
      return null;
    String epc = extractString(info, new String[] { "getEpc", "getEPC" });
    if (epc == null) {
      Object f = tryGetField(info, "epc");
      if (f != null)
        epc = String.valueOf(f);
    }
    if (epc == null || epc.isEmpty())
      epc = parseHexFromText(String.valueOf(info));
    if (epc == null || epc.isEmpty())
      return null;

    Integer rssi = extractInt(info, new String[] { "getRssi", "getRssiDbm", "getRssidBm", "getReadRssi", "getDbm" });
    if (rssi == null)
      rssi = parseRssiFromText(String.valueOf(info));
    if (rssi == null)
      rssi = -70;

    Map<String, Object> m = new HashMap<>();
    m.put("epc", epc);
    m.put("rssi", rssi);
    return m;
  }

  private Map<String, Object> pullOneTagOnce() {
    try {
      ensureReady();
      Object info = tryCall(uhfFunc, "readTagFromBuffer");
      if (info == null)
        info = tryCall(uhfFunc, "getTagFromBuffer");
      if (info == null)
        info = tryCall(uhfFunc, "popTagFromBuffer");
      if (info == null)
        info = tryCall(uhfFunc, "readBufferTag");
      if (info == null)
        info = tryCall(uhfFunc, "getOneTag");
      if (info == null)
        info = tryCall(uhfMgr, "readTagFromBuffer");
      if (info == null)
        info = tryCall(uhfMgr, "getTagFromBuffer");
      if (info == null)
        info = tryCall(uhfMgr, "popTagFromBuffer");
      if (info == null)
        info = tryCall(uhfMgr, "readBufferTag");
      if (info == null)
        info = tryCall(uhfMgr, "getOneTag");

      if (info == null) {
        Object list = tryCall(uhfFunc, "getTagList");
        if (list == null)
          list = tryCall(uhfMgr, "getTagList");
        if (list instanceof List && !((List<?>) list).isEmpty())
          info = ((List<?>) list).get(0);
        else if (list != null && list.getClass().isArray() && java.lang.reflect.Array.getLength(list) > 0)
          info = java.lang.reflect.Array.get(list, 0);
      }
      return mapFromInfo(info);
    } catch (Throwable t) {
      Log.e(TAG, "pullOne error", t);
      return null;
    }
  }

  private void publishTagFromInfo(Object info) {
    Map<String, Object> map = mapFromInfo(info);
    if (map == null)
      return;

    synchronized (tagCache) {
      tagCache.add(map);
      if (tagCache.size() > CACHE_LIMIT) {
        tagCache.subList(0, CACHE_LIMIT / 2).clear();
      }
    }
    if (sink != null)
      schedulePush(); // kirim batch di tick berikutnya
  }

  private List<Map<String, Object>> drainTagCache() {
    List<Map<String, Object>> snap = new ArrayList<>();
    synchronized (tagCache) {
      if (!tagCache.isEmpty()) {
        snap.addAll(tagCache);
        tagCache.clear();
      }
    }
    return snap;
  }

  // ---------------- Reflection helpers ----------------
  private Method findMethod(Object target, String name, int paramCount) {
    if (target == null)
      return null;
    Class<?> c = target.getClass();
    for (Method m : c.getMethods()) {
      if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
        m.setAccessible(true);
        return m;
      }
    }
    for (Method m : c.getDeclaredMethods()) {
      if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
        m.setAccessible(true);
        return m;
      }
    }
    return null;
  }

  private boolean invokeAny(Object target, String method, Object... args) throws Exception {
    Method m = findMethod(target, method, args == null ? 0 : args.length);
    if (m == null)
      return false;
    m.invoke(target, args);
    return true;
  }

  private Object tryCall(Object target, String method, Object... args) {
    try {
      Method m = findMethod(target, method, args == null ? 0 : args.length);
      if (m == null)
        return null;
      return m.invoke(target, args);
    } catch (Throwable ignore) {
      return null;
    }
  }

  private Object tryGetField(Object target, String name) {
    if (target == null)
      return null;
    try {
      Field f = target.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(target);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private String extractString(Object obj, String[] getters) {
    for (String g : getters) {
      Object v = tryCall(obj, g);
      if (v instanceof String)
        return (String) v;
    }
    return null;
  }

  private Integer extractInt(Object obj, String[] getters) {
    for (String g : getters) {
      Object v = tryCall(obj, g);
      if (v instanceof Integer)
        return (Integer) v;
      if (v instanceof Number)
        return ((Number) v).intValue();
    }
    return null;
  }

  private void dumpAllMethods(String label, Object o) {
    if (o == null) {
      Log.d(TAG, "🔎 " + label + " = null");
      return;
    }
    Log.d(TAG, "🔎 Methods of " + label + " (" + o.getClass().getName() + "):");
    for (Method m : o.getClass().getMethods()) {
      Log.d(TAG,
          " • " + m.getReturnType().getSimpleName() + " " + m.getName() + "() params:" + m.getParameterTypes().length);
    }
  }

  // --------- Link settings cepat (optional) ---------
  private void tuneForSpeed() {
    Object[] H = new Object[] { uhfFunc, uhfMgr, gClient };
    for (Object h : H)
      if (h != null) {
        // EPC only
        tryCall(h, "setReadTid", false);
        tryCall(h, "setReadUser", false);
        tryCall(h, "setBankEnable", 1); // 1 = EPC (pola vendor)
        tryCall(h, "setInventoryMode", 0); // EPC-only
        // Continuous fast
        tryCall(h, "setWorkMode", 1);
        tryCall(h, "setWorkingMode", 1);
        tryCall(h, "setInventoryContinue", 1);
        tryCall(h, "setContinuousMode", true);
        tryCall(h, "setSpeed", 0); // 0 = fast
        tryCall(h, "setBeeper", false); // matikan beep SDK (kita yang handle)
        // Anti-collision
        tryCall(h, "setSession", 0);
        tryCall(h, "setTarget", 0);
        tryCall(h, "setQ", 4);
        tryCall(h, "setDynamicQ", true);
        // Link profile (jika ada)
        tryCall(h, "setProfile", 2);
        tryCall(h, "setDR", 640);
        tryCall(h, "setM", 2);
      }
    Log.d(TAG, "tuneForSpeed applied");
  }

  @Override
  protected void onDestroy() {
    try {
      stopInventory();
    } catch (Throwable ignore) {
    }
    try {
      invokeAny(uhfMgr, "setPowerState_UHF", false);
    } catch (Throwable ignore) {
    }
    try {
      if (toneGen != null) {
        toneGen.release();
        toneGen = null;
      }
    } catch (Throwable ignore) {
    }
    super.onDestroy();
  }
}
