# libarchive_jni.c finds these by name.
-keepclassmembers class io.github.yuubiz.hirari.core.archive.NativeArchive {
    native <methods>;
    int read(long, int);
    void header(byte[], long, boolean, boolean, java.lang.String);
    boolean data(java.nio.ByteBuffer, long);
}
