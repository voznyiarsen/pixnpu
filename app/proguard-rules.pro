-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.lite.adapter.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn com.google.ai.edge.**