# AGP 9 only accepts the optimize defaults file; keep the previous shrink-only behaviour.
-dontoptimize
# Shrink unused code but do not rename anything: Health Connect record times are read reflectively.
-dontobfuscate
-keepclassmembers class androidx.health.connect.client.records.** {
    public *** getStartTime();
    public *** getEndTime();
    public *** getTime();
}
-keep class kotlinx.serialization.** { *; }
-keep @kotlinx.serialization.Serializable class nl.mteij.healthbridge.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# WorkManager's Room database is created reflectively at startup; without these the app dies on launch
# with NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
