# 1. Keep Generic Signatures (CRITICAL for Gson TypeToken)
-keepattributes Signature

# 2. Keep Annotation info (Required for @Keep, @SerializedName, etc.)
-keepattributes *Annotation*

# 3. Keep standard Gson classes
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

# 4. Keep YOUR Data Models
# This prevents renaming fields like 'name' or 'widgets' to 'a', 'b', etc.
-keep class mini.remote.deck.project.hobby.RemoteProfile { *; }
-keep class mini.remote.deck.project.hobby.RemoteWidget { *; }
-keep class mini.remote.deck.project.hobby.WidgetScript { *; }
-keep class mini.remote.deck.project.hobby.GridPosition { *; }
-keep class mini.remote.deck.project.hobby.GridSize { *; }
-keep class mini.remote.deck.project.hobby.Command { *; }
-keep class mini.remote.deck.project.hobby.CharacterRequest { *; }
-keep class mini.remote.deck.project.hobby.ClickRequest { *; }
-keep class mini.remote.deck.project.hobby.ScrollRequest { *; }
-keep class mini.remote.deck.project.hobby.HScrollGestureRequest { *; }
-keep class mini.remote.deck.project.hobby.KeyPressRequest { *; }
-keep class mini.remote.deck.project.hobby.MediaKeyRequest { *; }

# 5. Keep IdentifyResponse (used in Scan)
-keep class mini.remote.deck.project.hobby.MainViewModel$IdentifyResponse { *; }
