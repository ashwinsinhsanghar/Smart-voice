package com.google.android.apps.speech.tts.googletts.local.greco3;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.android.apps.speech.tts.googletts.audio.AndroidDecoder;

/**
 * Required by libgoogle_speech_sbg_tts_jni.so: the library binds to this exact class name and looks up
 * these fields and methods by name. Do not rename the native methods, audioAvailable/timepointAvailable,
 * assetManager or directAudioBuffer (see proguard-project.txt).
 *
 * The protocol messages (markup, session, config) are handed over as serialized protobuf bytes.
 */
public final class AndroidComposerImpl {

    private static final String TAG = "AndroidComposerImpl";

    /** Output of the engine: 16-bit mono PCM at this rate. */
    public static final int SAMPLE_RATE = 24000;

    /** Status codes returned by jniSyncReadBuffered. */
    public static final int STATUS_DONE = 2;
    public static final int STATUS_AUDIO = 3;

    /** Size of the "audio buffer" value in the session message (512) times two bytes per sample. */
    private static final int SESSION_BUFFER_BYTES = 1024;

    private final AndroidDecoder decoder;
    private final AssetManager assetManager;
    private final ArrayList<Object> timepoints = new ArrayList<Object>();
    private final ByteBuffer directAudioBuffer;
    private long handle = 0L;
    private boolean initialized = false;

    static {
        System.loadLibrary("google_speech_sbg_tts_jni");
    }

    public AndroidComposerImpl(Context context, AndroidDecoder decoder) {
        this.directAudioBuffer = ByteBuffer.allocateDirect(4096);
        this.directAudioBuffer.limit(0);
        this.assetManager = context.getAssets();
        this.decoder = decoder;
    }

    // ---------------------------------------------------------------- called from native code

    private boolean audioAvailable(int size, int sampleRate) {
        if (directAudioBuffer.hasRemaining()) {
            Log.e(TAG, "audioAvailable: Last audio chunk was not consumed, cannot write new audio chunk.");
            return false;
        }
        if (sampleRate != SAMPLE_RATE) {
            Log.e(TAG, "audioAvailable: Unexpected output sample rate.");
            return false;
        }
        directAudioBuffer.limit(size);
        return true;
    }

    private boolean timepointAvailable(int a, int b, int c, int d, double e) {
        return true;
    }

    // ---------------------------------------------------------------- public API

    /** Loads the voice: pipeline.pb (as an open descriptor), the voice directory and the engine config. */
    public boolean init(ParcelFileDescriptor pipeline, String directory, byte[] config) {
        long decoderHandle = decoder.jniConstruct();
        decoder.handle = decoderHandle;
        if (!decoder.jniInit(decoderHandle)) {
            Log.e(TAG, "initComposer: Failed to initialize decoder");
            return false;
        }
        handle = jniConstruct(decoderHandle);
        int fd = pipeline.getFd();
        boolean ok = false;
        if (config != null)
            ok = jniInitWithOverride(handle, fd, directory, config);
        if (!ok)
            ok = jniInit(handle, fd, directory);
        if (!ok) {
            Log.e(TAG, "initComposer: Failed to load pipeline definitions!");
            return false;
        }
        initialized = true;
        return true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** Starts one buffered synthesis. */
    public boolean setupSession(byte[] markup, byte[] session) {
        if (SESSION_BUFFER_BYTES > directAudioBuffer.capacity())
            return false;
        if (!jniSyncSetupBuffered(handle, markup, session)) {
            Log.e(TAG, "initBufferedSession: Failed to init buffered synthesis through composer!");
            return false;
        }
        return true;
    }

    /**
     * Produces the next chunk. Returns the engine status: STATUS_AUDIO (more to come),
     * STATUS_DONE (finished) or anything else (error). Audio, if any, is taken with takeAudio().
     */
    public int readBuffered() {
        if (directAudioBuffer.hasRemaining()) {
            Log.e(TAG, "readBuffered: Can't start new read while audio is available from last read.");
            return -1;
        }
        timepoints.clear();
        int status = jniSyncReadBuffered(handle);
        if (status != STATUS_AUDIO)
            reset();
        return status;
    }

    public boolean hasAudio() {
        return directAudioBuffer.hasRemaining();
    }

    public byte[] takeAudio() {
        byte[] data = new byte[directAudioBuffer.remaining()];
        directAudioBuffer.get(data);
        directAudioBuffer.rewind();
        directAudioBuffer.limit(0);
        return data;
    }

    public void reset() {
        directAudioBuffer.limit(0);
        timepoints.clear();
    }

    public synchronized void release() {
        if (handle != 0L) {
            jniDelete(handle);
            handle = 0L;
            initialized = false;
        }
        decoder.delete();
    }

    @Override
    protected void finalize() {
        release();
    }

    // ---------------------------------------------------------------- native

    private native long jniConstruct(long decoderHandle);

    private native void jniDelete(long handle);

    private native boolean jniInit(long handle, int fd, String directory);

    private native boolean jniInitWithOverride(long handle, int fd, String directory, byte[] config);

    private native int jniSyncReadBuffered(long handle);

    private native boolean jniSyncSetupBuffered(long handle, byte[] markup, byte[] session);

}
