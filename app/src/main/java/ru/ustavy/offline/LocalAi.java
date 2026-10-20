package ru.ustavy.offline;
public final class LocalAi {
 static { System.loadLibrary("ustavy"); }
 public static native byte[] generate(byte[] path, byte[] prompt);
 public static native void reset();
 public static native void cancel();
}
