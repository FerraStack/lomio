# JNI entry points and the objects native code calls back into.
-keep class dev.lomio.llama.NativeBridge { native <methods>; }
-keep class dev.lomio.llama.NativeBridge$UpdateCallback { *; }
-keep class * implements dev.lomio.llama.NativeBridge$UpdateCallback { void onUpdate(byte[], byte[]); }
-keep class dev.lomio.llama.LlamaException { <init>(java.lang.String); }
