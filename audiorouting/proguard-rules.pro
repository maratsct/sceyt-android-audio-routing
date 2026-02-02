# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep public API classes
-keep class com.sceyt.audiorouting.AudioRouter { *; }
-keep class com.sceyt.audiorouting.AudioDevice { *; }
-keep class com.sceyt.audiorouting.AudioDevice$* { *; }
-keep class com.sceyt.audiorouting.AudioRouterConfig { *; }
-keep class com.sceyt.audiorouting.AudioRouterListener { *; }
-keep class com.sceyt.audiorouting.RoutingState { *; }
