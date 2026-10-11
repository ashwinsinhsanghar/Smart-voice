package com.google.android.apps.speech.tts.googletts.audio;

import java.nio.ByteBuffer;

/**
 * Required by libgoogle_speech_sbg_tts_jni.so: the library binds to this exact class name and looks up
 * these fields and methods by name. Do not rename anything here (see proguard-project.txt).
 */
public final class AndroidDecoder {

    private static final String TAG = "AndroidDecoder";

    public final int capacity;
    public final ByteBuffer directAudioBuffer;
    public long handle;

    public AndroidDecoder() {
        handle = 0L;
        directAudioBuffer = ByteBuffer.allocateDirect(1024);
        capacity = directAudioBuffer.capacity();
    }

    // Called from native code.
    private boolean isReady() {
        return true;
    }

    // Called from native code (compressed audio decoding is not used for local voices).
    private void startDecodeByteArray(byte[] data) {
    }

    // Called from native code (network audio is not used for local voices).
    private void startDecodeHttpUrl(String url, long id) {
    }

    public synchronized void delete() {
        if (handle != 0L) {
            jniDelete(handle);
            handle = 0L;
        }
    }

    @Override
    protected void finalize() {
        delete();
    }

    private native void jniDelete(long handle);

    public native long jniConstruct();

    public native int jniDecoderAudioAvailable(long handle, int size);

    public native void jniDecoderCompleted(long handle);

    public native boolean jniDecoderStopped(long handle, int reason);

    public native boolean jniInit(long handle);

}
