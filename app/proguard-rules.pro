# Javet resolves classes and callbacks through JNI and reflection.
-keep class com.caoccao.javet.** { *; }
-keep interface com.caoccao.javet.** { *; }
-keepclassmembers class * {
    @com.caoccao.javet.annotations.** <methods>;
    @com.caoccao.javet.annotations.** <fields>;
}
