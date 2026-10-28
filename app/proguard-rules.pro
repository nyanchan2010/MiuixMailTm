-keepattributes *Annotation*,InnerClasses,Signature
-dontwarn kotlinx.serialization.**
-keepclassmembers class **$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep class app.privacy.miuixmailtm.data.** { *; }
