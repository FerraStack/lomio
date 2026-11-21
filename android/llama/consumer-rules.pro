# JNI entry points and the objects native code calls back into.
-keep class dev.lomio.llama.NativeBridge { native <methods>; }
-keep class dev.lomio.llama.NativeBridge$UpdateCallback { *; }
-keep class * implements dev.lomio.llama.NativeBridge$UpdateCallback {
  void onUpdate(byte[], byte[]);
  void onPromptProgress(int, int);
}
-keep class dev.lomio.llama.LlamaException { <init>(java.lang.String); }
