# R8 rules for the release build.
#
# Everything here exists because R8 breaks it otherwise, usually at runtime rather
# than at build time — which is the dangerous kind. A release build that compiles,
# installs and then loses your sync log is worse than one that fails loudly.

# ---------------------------------------------------------------- kotlinx.serialization
#
# The sync event log is serialised through generated $$serializer classes that nothing
# references statically — R8 sees them as dead code and strips them. The failure is
# silent and total: every log line fails to encode, sync quietly stops working, and
# nothing in the UI says so.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.manuel.ours.**$$serializer { *; }
-keepclassmembers class com.manuel.ours.** {
    *** Companion;
}
-keepclasseswithmembers class com.manuel.ours.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------------------- SQLCipher
#
# Loaded via System.loadLibrary and reached through JNI, so the Java side has no
# visible callers. Stripping it means the database cannot open at all.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.**

# ----------------------------------------------------------------------------- ZXing
# Reflective format lookup in MultiFormatReader.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ------------------------------------------------------------------------------ Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------------ Hilt / WorkManager
# Workers are constructed by name from a string in the WorkManager database.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# --------------------------------------------------------------------- Nearby / Play
-dontwarn com.google.android.gms.**

# Keep the enum names that are persisted as strings in the database and in the sync
# payload. valueOf() on a renamed enum throws, and every stored row would fail to read.
-keepclassmembers enum com.manuel.ours.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
