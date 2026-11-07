# JNI resolves class/method names and constructs error/event objects by name.
# Keep the small vendored Java facade stable if a consuming app enables R8.
-keep class org.torproject.onionmasq.** { *; }
