# Hermes app
-dontwarn java.**
-keep class ai.hermes.app.** { *; }

# GeckoView - keep all native bindings and runtime classes
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.geckoview.** { *; }
-keep class mozilla.components.** { *; }
-keepclassmembers class org.mozilla.gecko.** { *; }
-keepclassmembers class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.gecko.**
-dontwarn org.mozilla.geckoview.**

# Native methods used by GeckoView via JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
