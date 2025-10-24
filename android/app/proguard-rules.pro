# --- Keep entry points Flutter & app ---
-keep class io.flutter.** { *; }
-keep class com.example.rfid_03.** { *; }

-dontwarn com.rscja.**

# Jika library-nya ada, jangan diobfuscate/strip
-keep class com.rscja.** { *; }
-keep class com.gigatms.** { *; }
-keep class com.idata.** { *; }
-keep class a.a.a.** { *; }
-keep class com.uhf.** { *; }
-keep class com.uhf.base.** { *; }
-keep class com.idata.gg.reader.** { *; }
-keep class com.idata.gg.reader.api.** { *; }
-keep class com.**.UHFManager { *; }
-keep class com.idata.gg.** { *; }
-keep class com.gg.reader.api.** { *; }
-keep class cn.com.example.rfid_driver.** { *; }   # ★ JNI driver
-keep class com.r2000.** { *; }
-dontwarn com.uhf.**,com.idata.gg.reader.api.**,com.gg.reader.api.**,cn.com.example.rfid_driver.**,com.r2000.**


# Simpan metadata yang sering dipakai reflection
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

# Jika Anda reflect ke member MainActivity, jangan di-strip
-keepclassmembers class com.example.rfid_03.MainActivity { *; }
