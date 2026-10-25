# AWS SDK for Kotlin ships its own consumer rules; keep only warnings quiet.
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn io.netty.**

# ONNX Runtime is called through JNI by class and field name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
