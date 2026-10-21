# LibGDX keeps classes referenced reflectively by its backends.
-keep class com.badlogic.gdx.backends.android.** { *; }
-keep class com.badlogic.gdx.graphics.g2d.freetype.** { *; }
-dontwarn com.badlogic.gdx.**
# kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.sortopia.** { *** Companion; }
-keepclasseswithmembers class com.sortopia.** { kotlinx.serialization.KSerializer serializer(...); }
# Strip debug/verbose logging from release builds.
-assumenosideeffects class android.util.Log { public static int d(...); public static int v(...); public static int i(...); }
