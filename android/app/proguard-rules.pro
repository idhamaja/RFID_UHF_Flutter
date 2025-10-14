# --- Keep entry points Flutter & app ---
-keep class io.flutter.** { *; }
-keep class com.example.rfid_03.** { *; }

# --- iData & RSJCA vendor SDK (diakses via reflection / ada di device) ---
# Jangan gagal walau kelasnya tidak ada saat build
-dontwarn com.idata.**
-dontwarn com.rscja.**

# Jika library-nya ada, jangan diobfuscate/strip
-keep class com.idata.** { *; }
-keep class com.rscja.** { *; }

# Simpan metadata yang sering dipakai reflection
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

# Jika Anda reflect ke member MainActivity, jangan di-strip
-keepclassmembers class com.example.rfid_03.MainActivity { *; }
