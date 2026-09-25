# Compose and AndroidX ship their own consumer rules.

# Vosk goes through JNA, which binds native functions to these classes by reflection.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
