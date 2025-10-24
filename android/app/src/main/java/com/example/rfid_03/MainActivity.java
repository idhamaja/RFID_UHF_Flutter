package com.example.rfid_03;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

/**
 * UHF Native Bridge (high-speed):
 * - Continuous read + adaptive Q
 * - 1..4 Hz "burst snapshot" (EPC unik per jendela)
 * - Fallback drains (text/raw/bruteforce) untuk berbagai SDK
 */
public class MainActivity extends FlutterActivity {

  private static final String METHOD_CH = "uhf";
  private static final String EVENT_CH = "uhf/tags";
  private static final String TAG = "UHF";

  // pacing & limits
  private static final int CACHE_LIMIT = 12000;
  private static final int PUSH_CHUNK = 256;
  private static final int PUSH_GAP_MS = 3; // lebih rapat
  private static final int READER_IDLE_MS = 0;
  private static final int BEEP_GAP_MS = 200;
  private static final int VIB_GAP_MS = 240;
  private static final int DUP_SUPPRESS_MS = 4; // redup duplikat lebih agresif
  private static final int[] SERIAL_BAUD = new int[] { 921600, 460800, 230400, 115200 };

  // fast start
  private static final int FASTSTART_MS = 1500;
  private static final int FIRST_HIT_DEADLINE_MS = 800;

  // RSSI (dBm)
  private static final int RSSI_FAST_DBM = -90; // longgar saat warmup
  private static final int RSSI_STEADY_DBM = -62;

  // burst config
  private static final int BURST_MIN_MS = 220; // ~4.5 Hz maksimum
  private static final int BURST_MAX_MS = 1000; // 1 Hz minimum
  private static final float SNAPSHOT_WINDOW_RATIO = 0.86f; // porsi periode untuk kumpulkan EPC unik

  private boolean isBeepEnabled = false;
  private boolean isVibrateEnabled = false;

  private final Handler main = new Handler(Looper.getMainLooper());
  private HandlerThread pushThread;
  private Handler push;
  private HandlerThread rpcThread;
  private Handler rpc;

  private EventChannel.EventSink sink;

  private Object uhfMgr;
  private Object uhfFunc;
  private Object gClient;

  private final List<Map<String, Object>> tagCache = new ArrayList<>();
  private long lastPushAt = 0L;
  private boolean pushPosted = false;

  private final LinkedHashMap<String, Long> recentEpc = new LinkedHashMap<String, Long>(1024, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
      return size() > 4096;
    }
  };

  private volatile boolean powered = false, opened = false, running = false;

  private Thread readerThread;

  private ToneGenerator toneGen;
  private long lastBeepAt = 0L, lastVibrateAt = 0L;

  private long lastExpensivePollAt = 0L, lastQAdjustAt = 0L;
  private int hitsSinceLastAdjust = 0, currentQ = 3;

  private volatile boolean fastStart = false;
  private long fastStartEndsAt = 0L;
  private Thread targetThread;
  private volatile boolean targetLoop = false;

  private long lastNudgeAt = 0L;

  private volatile boolean seenAny = false;
  private long firstSeenAt = 0L;
  private volatile int currentGateDbm = RSSI_FAST_DBM;

  private volatile boolean firstPushDone = false;

  // snapshot / burst
  private volatile boolean fullScanMode = false;
  private long fullScanEndsAt = 0L;
  private final LinkedHashMap<String, Map<String, Object>> primeSet = new LinkedHashMap<>();

  private volatile boolean burstEnabled = false;
  private int burstMs = 1000;
  private final Runnable burstTask = new Runnable() {
    @Override
    public void run() {
      if (!running || !burstEnabled)
        return;
      if (!fullScanMode) {
        int window = Math.max(180, (int) (burstMs * SNAPSHOT_WINDOW_RATIO));
        beginFullScan(window);
      }
      main.postDelayed(this, burstMs);
    }
  };

  @Override
  public void configureFlutterEngine(@NonNull FlutterEngine engine) {
    super.configureFlutterEngine(engine);

    new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), METHOD_CH)
        .setMethodCallHandler((call, result) -> {
          try {
            switch (call.method) {
              case "startInventory": {
                final Boolean full = call.argument("fullScan");
                final Integer win = call.argument("windowMs");
                final Number hz = call.argument("scanHz"); // boleh int/double

                rpc.post(() -> {
                  try {
                    // burst config (optional)
                    if (Boolean.TRUE.equals(full)) {
                      double targetHz = (hz == null ? 1.0 : hz.doubleValue());
                      int period = (int) Math.round(1000.0 / Math.max(0.5, Math.min(4.5, targetHz)));
                      burstMs = Math.max(BURST_MIN_MS, Math.min(BURST_MAX_MS, period));
                      burstEnabled = true;
                      main.removeCallbacks(burstTask);
                      main.post(burstTask);

                      int w = (win != null) ? win : (int) (burstMs * SNAPSHOT_WINDOW_RATIO);
                      beginFullScan(w);
                    } else {
                      burstEnabled = false;
                      main.removeCallbacks(burstTask);
                    }

                    startInventoryCore();
                  } catch (Throwable t) {
                    Log.e(TAG, "startInventory error", t);
                  }
                });
                result.success(null);
                break;
              }

              case "stopInventory":
                burstEnabled = false;
                main.removeCallbacks(burstTask);
                stopInventoryCore();
                result.success(null);
                break;

              case "pullBatch":
                if (fullScanMode) {
                  result.success(new ArrayList<>());
                  break;
                }
                List<Map<String, Object>> out = readBatchOnce();
                out.addAll(drainTagCache());
                if (!out.isEmpty()) {
                  safeBeep();
                  safeVibrate(14);
                }
                result.success(out);
                break;

              case "setPower": {
                Integer p = call.argument("power");
                setPower(p == null ? 30 : Math.max(5, Math.min(30, p)));
                result.success(null);
                break;
              }

              case "setBeep":
                isBeepEnabled = Boolean.TRUE.equals(call.argument("enabled"));
                result.success(null);
                break;

              case "setVibrate":
                isVibrateEnabled = Boolean.TRUE.equals(call.argument("enabled"));
                result.success(null);
                break;

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
          public void onListen(Object args, EventChannel.EventSink es) {
            sink = es;
          }

          @Override
          public void onCancel(Object args) {
            sink = null;
          }
        });

    pushThread = new HandlerThread("uhf-push", android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
    pushThread.start();
    push = new Handler(pushThread.getLooper());

    rpcThread = new HandlerThread("uhf-rpc", android.os.Process.THREAD_PRIORITY_DEFAULT);
    rpcThread.start();
    rpc = new Handler(rpcThread.getLooper());
  }

  /* ===================== FULL-SCAN SNAPSHOT ===================== */

  private void beginFullScan(int windowMs) {
    fullScanMode = true;
    synchronized (primeSet) {
      primeSet.clear();
    }
    fullScanEndsAt = SystemClock.uptimeMillis() + Math.max(180, windowMs);
    main.postDelayed(this::finishFullScanIfDue, windowMs);
  }

  private void finishFullScanIfDue() {
    if (!fullScanMode)
      return;
    long now = SystemClock.uptimeMillis();
    if (now < fullScanEndsAt) {
      main.postDelayed(this::finishFullScanIfDue, fullScanEndsAt - now);
      return;
    }
    List<Map<String, Object>> batch = new ArrayList<>();
    synchronized (primeSet) {
      batch.addAll(primeSet.values());
      primeSet.clear();
    }
    fullScanMode = false;
    if (sink != null && !batch.isEmpty()) {
      final List<Map<String, Object>> out = batch;
      main.post(() -> {
        try {
          sink.success(out);
        } catch (Throwable ignore) {
        }
      });
    }
    firstPushDone = true;
  }

  /* ===================== small utils ===================== */

  private void safeBeep() {
    if (!isBeepEnabled)
      return;
    long now = SystemClock.uptimeMillis();
    if (now - lastBeepAt < BEEP_GAP_MS)
      return;
    lastBeepAt = now;
    try {
      if (toneGen == null)
        toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
      toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 60);
    } catch (Throwable ignore) {
    }
  }

  private void safeVibrate(long ms) {
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
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
      else
        vib.vibrate(ms);
    } catch (Throwable ignore) {
    }
  }

  private void kickWarmBurst() {
    rpc.post(() -> {
      try {
        tryCall(uhfFunc, "setInventoryContinue", 1);
        tryCall(uhfMgr, "setInventoryContinue", 1);
        for (int i = 0; i < 3; i++) {
          tryCall(uhfFunc, "setTarget", (i & 1));
          tryCall(uhfMgr, "setTarget", (i & 1));
          if (i == 1 && tryCall(uhfFunc, "inventoryReset") == null) {
            invokeAny(uhfFunc, "inventoryStop");
            Thread.sleep(50);
            invokeAny(uhfFunc, "inventoryStart");
          }
          Thread.sleep(60);
        }
      } catch (Throwable ignore) {
      }
    });
  }

  private void startRescueDeadline(long ms) {
    new Thread(() -> {
      long end = SystemClock.uptimeMillis() + ms;
      while (running && SystemClock.uptimeMillis() < end && !seenAny) {
        try {
          Thread.sleep(40);
        } catch (Throwable ignore) {
        }
      }
      if (!running || seenAny)
        return;
      try {
        setMinRssiBoth(-78);
        configureRegionForBootstrap();
        if (tryCall(uhfFunc, "inventoryReset") == null) {
          invokeAny(uhfFunc, "inventoryStop");
          Thread.sleep(100);
          invokeAny(uhfFunc, "inventoryStart");
        }
        startTargetNudge(SystemClock.uptimeMillis() + 1200);
      } catch (Throwable ignore) {
      }
    }, "uhf-deadline").start();
  }

  private void setMinRssiBoth(int dbm) {
    tryCall(uhfFunc, "setRssiFilter", dbm);
    tryCall(uhfMgr, "setRssiFilter", dbm);
    tryCall(uhfFunc, "setMinRssi", dbm);
    tryCall(uhfMgr, "setMinRssi", dbm);
    currentGateDbm = dbm;
  }

  private static int normalizeToDbm(int v) {
    if (v > 0 && v <= 300)
      return -90 + (v * 60) / 300;
    return v;
  }

  /* ===================== START/STOP CORE ===================== */

  private void startInventoryCore() throws Exception {
    ensureReady();

    boolean started = invokeAny(uhfFunc, "startInventoryTag") ||
        invokeAny(uhfFunc, "inventoryStart") ||
        invokeAny(uhfFunc, "startRead") ||
        invokeAny(uhfMgr, "startInventoryTag") ||
        invokeAny(uhfMgr, "inventoryStart") ||
        invokeAny(uhfMgr, "startRead");

    running = true;
    fastStart = true;
    long now = SystemClock.uptimeMillis();
    fastStartEndsAt = now + FASTSTART_MS;
    lastQAdjustAt = now + 400; // adjust lebih cepat
    seenAny = false;
    firstSeenAt = 0L;
    firstPushDone = false;

    startReaderLoop();
    kickWarmBurst();
    startRescueDeadline(FIRST_HIT_DEADLINE_MS);

    setMinRssiBoth(RSSI_FAST_DBM);
    tryCall(uhfFunc, "setContinuousMode", true);
    tryCall(uhfMgr, "setContinuousMode", true);
    tryCall(uhfFunc, "setInventoryContinue", 1);
    tryCall(uhfMgr, "setInventoryContinue", 1);

    // Prefer session S0 (lebih cepat) bila ada API
    tryCall(uhfFunc, "setSession", 0);
    tryCall(uhfMgr, "setSession", 0);

    rpc.post(() -> {
      try {
        tryRegisterCallbacks();

        // phase 1: DynamicQ off, Q=0 (agresif)
        tryCall(uhfFunc, "setDynamicQ", false);
        tryCall(uhfMgr, "setDynamicQ", false);
        currentQ = 0;
        tryCall(uhfFunc, "setQ", currentQ);
        tryCall(uhfMgr, "setQ", currentQ);
        tryCall(uhfFunc, "SetQValue", currentQ);
        tryCall(uhfMgr, "SetQValue", currentQ);

        configureRegionForBootstrap(); // single channel sebentar

        Thread.sleep(900);

        // phase 2: dynamic on + RSSI gate normal
        tryCall(uhfFunc, "setDynamicQ", true);
        tryCall(uhfMgr, "setDynamicQ", true);
        setMinRssiBoth(RSSI_STEADY_DBM);
        restoreRegionAfterBootstrap();
        lastQAdjustAt = 0;
      } catch (Throwable ignore) {
      }
    });
  }

  private void stopInventoryCore() {
    running = false;
    fastStart = false;
    stopTargetNudge();
    main.removeCallbacks(burstTask);
    if (readerThread != null) {
      readerThread.interrupt();
      readerThread = null;
    }
    try {
      invokeAny(uhfFunc, "stopInventory");
      invokeAny(uhfFunc, "inventoryStop");
      invokeAny(uhfFunc, "stopRead");
      invokeAny(uhfMgr, "stopInventory");
      invokeAny(uhfMgr, "inventoryStop");
      invokeAny(uhfMgr, "stopRead");
    } catch (Throwable ignore) {
    }
  }

  private void configureRegionForBootstrap() {
    tryCall(uhfFunc, "setHopping", false);
    tryCall(uhfMgr, "setHopping", false);

    tryCall(uhfFunc, "setRegion", 1);
    tryCall(uhfMgr, "setRegion", 1);
    tryCall(uhfFunc, "setFreRegion", 1);
    tryCall(uhfMgr, "setFreRegion", 1);

    tryCall(uhfFunc, "setChannel", 6);
    tryCall(uhfMgr, "setChannel", 6);
    tryCall(uhfFunc, "setFrequency", 922625);
    tryCall(uhfMgr, "setFrequency", 922625);
    tryCall(uhfFunc, "setUserDefineFrequency", 922000, 923000, 250);
    tryCall(uhfMgr, "setFrequencyRegion", 920000, 925000, 500);

    tryCall(uhfFunc, "setProfile", 3);
    tryCall(uhfMgr, "setProfile", 3);
  }

  private void restoreRegionAfterBootstrap() {
    tryCall(uhfFunc, "setHopping", true);
    tryCall(uhfMgr, "setHopping", true);
  }

  /* ===================== READER LOOP ===================== */

  private void startReaderLoop() {
    if (readerThread != null)
      return;
    readerThread = new Thread(() -> {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
      int idleStreak = 0;
      while (running) {
        try {
          int c = drainAllTagsOnce();
          if (c == 0)
            c = expensiveSweep(); // fallback mahal tapi dipersempit intervalnya
          if (c == 0) {
            idleStreak = Math.min(idleStreak + 1, 200);
            int extra = (idleStreak < 4) ? 0 : (idleStreak < 24) ? 1 : (idleStreak < 60) ? 2 : 3;
            int sleep = READER_IDLE_MS + extra;
            if (sleep > 0)
              Thread.sleep(sleep);
          } else {
            idleStreak = 0;
          }
          maybeAdjustQ();
        } catch (InterruptedException e) {
          break;
        } catch (Throwable t) {
          try {
            Thread.sleep(6);
          } catch (InterruptedException ie) {
            break;
          }
        }
      }
    }, "uhf-reader");
    readerThread.start();
  }

  private void startTargetNudge(long untilMs) {
    if (targetThread != null)
      return;
    targetLoop = true;
    targetThread = new Thread(() -> {
      int t = 0;
      while (running && targetLoop && SystemClock.uptimeMillis() < untilMs) {
        try {
          int tgt = (t++ & 1);
          tryCall(uhfFunc, "setTarget", tgt);
          tryCall(uhfMgr, "setTarget", tgt);
          Thread.sleep(40);
        } catch (Throwable ignore) {
        }
      }
    }, "uhf-target-nudge");
    targetThread.start();
  }

  private void stopTargetNudge() {
    targetLoop = false;
    targetThread = null;
  }

  /* ===================== Hardware init ===================== */

  private void setPower(int dbm) {
    try {
      if (!invokeAny(uhfFunc, "setReadWritePower", dbm, dbm)
          && !invokeAny(uhfFunc, "powerSet", dbm)
          && !invokeAny(uhfMgr, "setReadWritePower", dbm, dbm)
          && !invokeAny(uhfMgr, "powerSet", dbm)) {
        Log.w(TAG, "power setter not found");
      }
    } catch (Throwable ignore) {
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

  private void initManagerFunction() throws Exception {
    Class<?> mgrClz = Class.forName("com.uhf.base.UHFManager");
    Method getter = null;
    for (Method m : mgrClz.getDeclaredMethods())
      if (m.getName().equals("getUHFImplSigleInstance")) {
        getter = m;
        break;
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
        Object[] c = p0.getEnumConstants();
        arg = (c != null && c.length > 0) ? c[0] : null;
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
    Log.d(TAG, "init: mgr=" + (uhfMgr != null) + ", func=" + (uhfFunc != null));
  }

  private void powerOn() {
    try {
      invokeAny(uhfMgr, "setPowerState_UHF", true);
      invokeAny(uhfMgr, "enableUartComm_UHF", true);
      invokeAny(uhfMgr, "powerOn");
      invokeAny(uhfFunc, "powerOn");
      powered = true;
      setPower(30);
    } catch (Throwable ignore) {
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
        Method open2 = gClz.getMethod("openAndroidSerial", String.class, int.class);
        String[] nodes = new String[] { "/dev/ttyS4", "/dev/ttyS3", "/dev/ttyHSL0", "/dev/ttyMT2" };
        outer: for (int baud : SERIAL_BAUD)
          for (String n : nodes) {
            try {
              open2.invoke(gClient, n, baud);
              ok = true;
              break outer;
            } catch (Throwable ignore) {
            }
          }
      } catch (NoSuchMethodException ignore) {
        try {
          Method open1 = gClz.getMethod("openAndroidSerial", int.class);
          for (int baud : SERIAL_BAUD) {
            try {
              open1.invoke(gClient, baud);
              ok = true;
              break;
            } catch (Throwable ignored) {
            }
          }
        } catch (Throwable ignore2) {
        }
      }
      opened = ok || true;
    } catch (Throwable t) {
      Log.w(TAG, "GClient not available: " + t.getMessage());
      opened = true;
    }
  }

  private void tryRegisterCallbacks() {
    try {
      final Class<?> logIface = (Class<?>) Class.forName("com.idata.gg.reader.api.dal.HandlerTagEpcLog");
      Object logProxy = Proxy.newProxyInstance(
          logIface.getClassLoader(), new Class[] { logIface },
          (proxy, method, args) -> {
            if ("log".equals(method.getName()) && args != null && args.length >= 1)
              publishTagFromInfo(args[args.length - 1]);
            return null;
          });

      final Class<?> overIface = (Class<?>) Class.forName("com.idata.gg.reader.api.dal.HandlerTagEpcOver");
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

  private boolean registerListenerOnHost(Object host, String name, Class iface, Object proxy) {
    if (host == null || iface == null)
      return false;
    try {
      for (Method m : host.getClass().getMethods()) {
        if (m.getName().equals(name)
            && m.getParameterTypes().length == 1
            && m.getParameterTypes()[0].isAssignableFrom(iface)) {
          m.setAccessible(true);
          m.invoke(host, proxy);
          return true;
        }
      }
    } catch (Throwable ignore) {
    }
    return false;
  }

  private void maybeAdjustQ() {
    long now = SystemClock.uptimeMillis();
    if (now - lastQAdjustAt < 200)
      return; // lebih responsif
    lastQAdjustAt = now;

    int rate = hitsSinceLastAdjust;
    hitsSinceLastAdjust = 0;
    int backlog;
    synchronized (tagCache) {
      backlog = tagCache.size();
    }

    int newQ = currentQ;
    if (rate > 600)
      newQ = 6;
    else if (rate > 240)
      newQ = Math.max(newQ, 5);
    else if (rate > 100)
      newQ = Math.max(newQ, 4);
    else if (rate < 35 && backlog < 80)
      newQ = 3;

    if (backlog > 1500)
      newQ = 6;
    else if (backlog > 700)
      newQ = Math.max(newQ, 5);
    else if (backlog > 260)
      newQ = Math.max(newQ, 4);

    if (newQ != currentQ) {
      tryCall(uhfFunc, "setQ", newQ);
      tryCall(uhfMgr, "setQ", newQ);
      tryCall(uhfFunc, "SetQValue", newQ);
      tryCall(uhfMgr, "SetQValue", newQ);
      currentQ = newQ;
      Log.d(TAG, "Adaptive Q -> " + newQ);
    }
  }

  private void schedulePush() {
    if (sink == null || pushPosted || fullScanMode)
      return;
    pushPosted = true;
    long delay = Math.max(0, PUSH_GAP_MS - (SystemClock.uptimeMillis() - lastPushAt));

    push.postDelayed(() -> {
      List<Map<String, Object>> batch = new ArrayList<>();
      synchronized (tagCache) {
        int n = Math.min(PUSH_CHUNK, tagCache.size());
        if (n > 0) {
          batch.addAll(tagCache.subList(0, n));
          tagCache.subList(0, n).clear();
        }
      }

      pushPosted = false;
      if (sink == null)
        return;
      if (batch.isEmpty()) {
        synchronized (tagCache) {
          if (!tagCache.isEmpty())
            schedulePush();
        }
        return;
      }
      lastPushAt = SystemClock.uptimeMillis();

      main.post(() -> {
        try {
          sink.success(batch);
          hitsSinceLastAdjust += batch.size();
        } catch (Throwable t) {
          Log.w(TAG, "push error", t);
        }

        safeVibrate(14);
        safeBeep();

        int backlogAfter;
        synchronized (tagCache) {
          backlogAfter = tagCache.size();
        }
        long now1 = SystemClock.uptimeMillis();
        if (batch.size() < 8 && backlogAfter > 500 && now1 - lastNudgeAt > 380) {
          lastNudgeAt = now1;
          rpc.post(() -> {
            try {
              if (tryCall(uhfFunc, "inventoryReset") == null) {
                invokeAny(uhfFunc, "inventoryStop");
                invokeAny(uhfFunc, "inventoryStart");
              }
            } catch (Throwable ignore) {
            }
          });
        }
        synchronized (tagCache) {
          if (!tagCache.isEmpty())
            schedulePush();
        }
      });
    }, delay);
  }

  /* ===================== drains ===================== */

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
        "readTagFromBuffer", "getTagFromBuffer", "popTagFromBuffer", "inventoryReadTagFromBuffer",
        "readBufferTag", "getOneTag", "readUhfBufferTag", "readTagFromBufferByOnce", "getEpcFromBuffer"
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
    String[] names = new String[] { "getTagList", "getTags", "readBuffer", "getInventoryTagList", "getInventoryTag",
        "inventoryBuffer" };
    int c = 0;
    for (String n : names) {
      Object list = tryCall(host, n);
      if (list == null)
        continue;
      if (list instanceof List) {
        for (Object t : (List<?>) list) {
          publishTagFromInfo(t);
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

  private int expensiveSweep() {
    long now = SystemClock.uptimeMillis();
    if (now - lastExpensivePollAt < 60)
      return 0; // lebih sering
    lastExpensivePollAt = now;

    int c = 0;
    c += sweepText(uhfFunc);
    c += sweepText(uhfMgr);
    c += sweepText(gClient);

    c += sweepRaw(uhfFunc);
    c += sweepRaw(uhfMgr);
    c += sweepRaw(gClient);

    c += sweepBrute(uhfFunc);
    c += sweepBrute(uhfMgr);
    c += sweepBrute(gClient);
    return c;
  }

  private int sweepText(Object host) {
    if (host == null)
      return 0;
    String[] names = new String[] { "readEpcLog", "readTagText", "readLog", "getTagEpcLog", "getEpcTxt", "getLogString",
        "getLog" };
    int c = 0;
    for (String n : names) {
      Object v = tryCall(host, n);
      if (v instanceof String) {
        Map<String, Object> m = mapFromInfo(v);
        if (m != null) {
          publishTagFromMap(m);
          c++;
        }
      }
    }
    return c;
  }

  private int sweepRaw(Object host) {
    if (host == null)
      return 0;
    String[] names = new String[] { "readBuffer", "getBuffer", "getReadBuf", "readTagBuffer", "getInventoryBuffer" };
    int c = 0;
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
      Map<String, Object> m = mapFromInfo(text);
      if (m != null) {
        publishTagFromMap(m);
        c++;
      }
    }
    return c;
  }

  private int sweepBrute(Object host) {
    if (host == null)
      return 0;
    int c = 0;
    for (Method m : host.getClass().getMethods()) {
      try {
        if (m.getParameterTypes().length != 0)
          continue;
        String mn = m.getName().toLowerCase();
        if (!(mn.contains("tag") || mn.contains("epc") || mn.contains("buf") || mn.contains("log")))
          continue;
        m.setAccessible(true);
        Object v = m.invoke(host);
        if (v == null)
          continue;
        if (v instanceof String) {
          Map<String, Object> mm = mapFromInfo(v);
          if (mm != null) {
            publishTagFromMap(mm);
            c++;
          }
          continue;
        }
        if (v instanceof List) {
          for (Object item : (List<?>) v) {
            Map<String, Object> mm = mapFromInfo(item);
            if (mm != null) {
              publishTagFromMap(mm);
              c++;
            }
          }
          continue;
        }
        if (v.getClass().isArray()) {
          int len = java.lang.reflect.Array.getLength(v);
          for (int i = 0; i < len; i++) {
            Object item = java.lang.reflect.Array.get(v, i);
            Map<String, Object> mm = mapFromInfo(item);
            if (mm != null) {
              publishTagFromMap(mm);
              c++;
            }
          }
          continue;
        }
        Map<String, Object> mm = mapFromInfo(v);
        if (mm != null) {
          publishTagFromMap(mm);
          c++;
        }
      } catch (Throwable ignore) {
      }
    }
    return c;
  }

  private List<Map<String, Object>> readBatchOnce() {
    List<Map<String, Object>> out = new ArrayList<>();
    drainToListBySinglePop(uhfFunc, out);
    drainToListBySinglePop(uhfMgr, out);
    if (!out.isEmpty())
      return out;

    drainToListByList(uhfFunc, out);
    drainToListByList(uhfMgr, out);
    if (!out.isEmpty())
      return out;

    long now = SystemClock.uptimeMillis();
    if (now - lastExpensivePollAt < 80)
      return out;
    lastExpensivePollAt = now;

    drainToListByTextLog(uhfFunc, out);
    drainToListByTextLog(uhfMgr, out);
    if (!out.isEmpty())
      return out;
    drainToListByTextLog(gClient, out);
    if (!out.isEmpty())
      return out;

    drainToListByRawBuffer(uhfFunc, out);
    drainToListByRawBuffer(uhfMgr, out);
    if (!out.isEmpty())
      return out;
    drainToListByRawBuffer(gClient, out);
    if (!out.isEmpty())
      return out;

    bruteForceDrain(uhfFunc, out);
    bruteForceDrain(uhfMgr, out);
    bruteForceDrain(gClient, out);
    return out;
  }

  private void drainToListBySinglePop(Object host, List<Map<String, Object>> out) {
    if (host == null)
      return;
    String[] names = new String[] {
        "readTagFromBuffer", "getTagFromBuffer", "popTagFromBuffer", "inventoryReadTagFromBuffer",
        "readBufferTag", "getOneTag", "readUhfBufferTag", "readTagFromBufferByOnce", "getEpcFromBuffer"
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
    String[] names = new String[] { "getTagList", "getTags", "readBuffer", "getInventoryTagList", "getInventoryTag",
        "inventoryBuffer" };
    for (String n : names) {
      Object list = tryCall(host, n);
      if (list == null)
        continue;
      if (list instanceof List) {
        for (Object t : (List<?>) list) {
          Map<String, Object> m = mapFromInfo(t);
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

  private void drainToListByTextLog(Object host, List<Map<String, Object>> out) {
    if (host == null)
      return;
    String[] names = new String[] { "readEpcLog", "readTagText", "readLog", "getTagEpcLog", "getEpcTxt", "getLogString",
        "getLog" };
    for (String n : names) {
      Object v = tryCall(host, n);
      if (v instanceof String) {
        Map<String, Object> m = mapFromInfo(v);
        if (m != null)
          out.add(m);
      }
    }
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
      Map<String, Object> m = mapFromInfo(text);
      if (m != null)
        out.add(m);
    }
  }

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
        m.setAccessible(true);
        Object v = m.invoke(host);
        if (v == null)
          continue;
        if (v instanceof String) {
          Map<String, Object> map = mapFromInfo(v);
          if (map != null)
            out.add(map);
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

  private void publishTagFromInfo(Object info) {
    Map<String, Object> map = mapFromInfo(info);
    if (map == null)
      return;
    publishTagFromMap(map);
  }

  private void publishTagFromMap(Map<String, Object> map) {
    if (!seenAny) {
      seenAny = true;
      firstSeenAt = SystemClock.uptimeMillis();
    }

    if (fullScanMode) {
      String epc = (String) map.get("epc");
      synchronized (primeSet) {
        primeSet.put(epc, map);
      }
      return; // ditahan dulu, kirim serentak saat window selesai
    }

    if (!firstPushDone) {
      firstPushDone = true;
      pushFirstNow(map);
    }

    String epc = (String) map.get("epc");
    long now = SystemClock.uptimeMillis();
    Long last = recentEpc.get(epc);
    if (last != null && (now - last) < DUP_SUPPRESS_MS)
      return;
    recentEpc.put(epc, now);

    synchronized (tagCache) {
      tagCache.add(map);
      if (tagCache.size() > CACHE_LIMIT)
        tagCache.subList(0, CACHE_LIMIT / 2).clear();
    }
    schedulePush();
  }

  private void pushFirstNow(Map<String, Object> first) {
    if (fullScanMode)
      return;
    if (sink == null || first == null)
      return;
    List<Map<String, Object>> one = new ArrayList<>(1);
    one.add(first);
    main.post(() -> {
      try {
        sink.success(one);
      } catch (Throwable ignore) {
      }
      safeBeep();
      safeVibrate(14);
    });
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

  /* ===================== parsing ===================== */

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
        .compile("(-?\\d{1,3})\\s*d?B?m?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
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

    Integer rssiRaw = extractInt(info, new String[] { "getRssi", "getRssiDbm", "getRssidBm", "getReadRssi", "getDbm" });
    if (rssiRaw == null)
      rssiRaw = parseRssiFromText(String.valueOf(info));
    boolean hasRssi = (rssiRaw != null);
    if (rssiRaw == null)
      rssiRaw = -70;
    int rssiDbm = normalizeToDbm(rssiRaw);

    int gate = currentGateDbm;
    if (hasRssi && rssiDbm < gate)
      return null;

    Map<String, Object> m = new HashMap<>();
    m.put("epc", epc);
    m.put("rssi", rssiRaw);
    m.put("rssiDbm", rssiDbm);
    return m;
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

  /* ===================== reflect helpers ===================== */

  private Method findMethod(Object target, String name, int paramCount) {
    if (target == null)
      return null;
    Class<?> c = target.getClass();
    for (Method m : c.getMethods())
      if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
        m.setAccessible(true);
        return m;
      }
    for (Method m : c.getDeclaredMethods())
      if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
        m.setAccessible(true);
        return m;
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
    } catch (Throwable ignore) {
      return null;
    }
  }

  @Override
  protected void onDestroy() {
    try {
      stopInventoryCore();
    } catch (Throwable ignore) {
    }
    try {
      invokeAny(uhfMgr, "setPowerState_UHF", false);
    } catch (Throwable ignore) {
    }
    try {
      if (pushThread != null) {
        pushThread.quitSafely();
        pushThread = null;
        push = null;
      }
    } catch (Throwable ignore) {
    }
    try {
      if (rpcThread != null) {
        rpcThread.quitSafely();
        rpcThread = null;
        rpc = null;
      }
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
