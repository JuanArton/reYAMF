-keep class com.mja.reyamf.** { *; }
-keepclassmembers class com.android.dx.dex.cf.CfTranslator { public static *** translate(...); }
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-dontwarn javax.annotation.Nonnull