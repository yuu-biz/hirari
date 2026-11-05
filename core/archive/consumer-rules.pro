# libarchive_jni.c finds these by name, and its symbol names spell out the class name.
-keep class io.github.yuubiz.hirari.core.archive.NativeArchive {
    native <methods>;
    int read(long, int);
    void header(byte[], long, boolean, boolean, java.lang.String, int, int, long);
    boolean data(java.nio.ByteBuffer, long);
}
