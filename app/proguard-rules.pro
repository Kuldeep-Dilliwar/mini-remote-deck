# ----------------------------------------------------------------------------
# 1. FIX BUILD ERRORS (OkHttp Dependencies)
# ----------------------------------------------------------------------------
# OkHttp references these, but they are optional. We suppress the warnings
# so R8 allows the build to finish.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.jce.provider.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ----------------------------------------------------------------------------
# 2. FIX RUNTIME CRASHES (Gson & Reflection)
# ----------------------------------------------------------------------------
# Keep Generic Signatures (CRITICAL for Gson TypeToken to work)
-keepattributes Signature

# Keep Annotation info (Required for @Keep, @SerializedName, etc.)
-keepattributes *Annotation*

# Keep standard Gson classes
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

# ----------------------------------------------------------------------------
# 3. PRESERVE YOUR DATA MODELS
# ----------------------------------------------------------------------------
# Prevent R8 from renaming the fields in your data classes.
# If these are renamed, Gson won't find the data when loading from JSON.

-keep class mini.remote.deck.project.hobby.RemoteProfile { *; }
-keep class mini.remote.deck.project.hobby.RemoteWidget { *; }
-keep class mini.remote.deck.project.hobby.WidgetScript { *; }
-keep class mini.remote.deck.project.hobby.GridPosition { *; }
-keep class mini.remote.deck.project.hobby.GridSize { *; }
-keep class mini.remote.deck.project.hobby.Command { *; }

# Request/Response classes used in networking
-keep class mini.remote.deck.project.hobby.CharacterRequest { *; }
-keep class mini.remote.deck.project.hobby.ClickRequest { *; }
-keep class mini.remote.deck.project.hobby.ScrollRequest { *; }
-keep class mini.remote.deck.project.hobby.HScrollGestureRequest { *; }
-keep class mini.remote.deck.project.hobby.KeyPressRequest { *; }
-keep class mini.remote.deck.project.hobby.MediaKeyRequest { *; }

# ViewModel helper classes (referenced via reflection/Gson)
-keep class mini.remote.deck.project.hobby.MainViewModel$IdentifyResponse { *; }
-keep class mini.remote.deck.project.hobby.MainViewModel$DiscoveredDevice { *; }
