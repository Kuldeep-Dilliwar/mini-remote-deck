# 1. Keep Generic Signatures for Gson
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# 2. Keep your Data Classes
# (Ensures R8 doesn't rename fields like 'name' to 'a')
-keep class mini.remote.deck.project.hobby.** { *; }

# 3. Keep Gson Internal Logic
-keep class com.google.gson.** { *; }
-keep interface com.google.gson.** { *; }

# FIX: Do NOT use "-keep class sun.misc.Unsafe".
# Just ignore the warning because this class is part of the OS, not your app.
-dontwarn sun.misc.Unsafe
-dontwarn com.google.gson.**

# 4. Keep Network Libs (Retrofit/OkHttp)
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn okio.**
-keep class retrofit2.** { *; }

# 5. Keep ViewModel Constructors
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
