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
