package aktech.tts.synth;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.os.ParcelFileDescriptor;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.speech.tts.googletts.audio.AndroidDecoder;
import com.google.android.apps.speech.tts.googletts.local.greco3.AndroidComposerImpl;

import aktech.tts.smartvoice.api.SoundFormatAdapter;
import aktech.tts.smartvoice.api.Speaker;
import aktech.tts.smartvoice.api.TtsEngine;
import aktech.tts.smartvoice.api.Utils;

/**
 * Google offline voices (libgoogle_speech_sbg_tts_jni.so, 24 kHz) for SmartVoice:
 *
 *   guj-IND-guf_05044   Gujarati, female
 *
 * The voice pack lives in assets/google/gu-in/... (same layout as the voice pack zip, without the
 * config.yaml / engines.yaml files). It is unpacked once to filesDir/google/gu-in because the native
 * engine reads the model files through file paths. To add another speaker of the pack (for example
 * gum_08407, male) add one more VoiceDef below.
 *
 * The native engine is created lazily (never on the UI thread): initialization loads several MB of models.
 */
public class GoogleTtsEngine implements TtsEngine {

    private static final String TAG = "SmartVoice-Google";
    private static final String ASSET_ROOT = "google";
    private static final String READY_MARKER = ".unpacked";

    private static final int AUDIO_CHUNK_SIZE = 4096;
    private static final int DEFAULT_VOLUME = 80;

    private static final int MIN_RATE = 50;
    private static final int MAX_RATE = 400;
    private static final int MIN_PITCH = 50;
    private static final int MAX_PITCH = 200;

    /** Engine configuration (serialized protobuf) taken over unchanged from the original integration. */
    private static final byte[] ENGINE_CONFIG = hex(
        "0a6b0a05656e2d474212620a330a066c626c73657112170a07656e2d47422e30" +
        "120c4522206c202e206220693a221a0e0a04656e2e3012066c626c7365712200" +
        "122b222912270a0f5e7c207c5b5b3a70756e63743a5d5d12024c421a08205b72" +
        "525d6f616422066c626c7365710a790a05656e2d55531270126e226c126a0a2d" +
        "5b5b3a64696769743a5d5d2b283f3a5c5c2e5b5b3a64696769743a5d5d293f20" +
        "3f283f3ac2b0467cc2b043293f1205203f2d203f1a2c5b5b3a64696769743a5d" +
        "5d2b283f3a5c5c2e5b5b3a64696769743a5d5d293f203f283f3ac2b0467cc2b0" +
        "4329220420746f20");

    /** Feature switches of the session message: name -> enabled. */
    private static final String[] FEATURES_OFF = {
        "VocaineSilenceMuter__enable_muting",
        "Darwinn__enable_darwinn",
        "Darwinn__compile_on_device",
        "Lemonbalm__enable_compile_on_device",
        "Lemonbalm__enable_lemonbalm_darwinn_rio",
        "Locomel__enable_compile_on_device",
        "Locomel__enable_havoc_on_32bit"
    };
    private static final String[] FEATURES_ON = {
        "Locomel__enable_havoc_on_64bit"
    };
    private static final String[] FEATURES_INT = {
        "Locomel__duration_delegate",
        "Locomel__mel_delegate",
        "Locomel__nvoc_delegate"
    };

    private static final class VoiceDef {
        final String language;   // ISO 639-2 (as used by SmartVoice)
        final String country;    // ISO 3166 alpha-3
        final String name;       // speaker id of the pack
        final String gender;
        final String folder;     // folder of the language inside the pack
        final String spec;

        VoiceDef(String language, String country, String name, String gender, String folder) {
            this.language = language;
            this.country = country;
            this.name = name;
            this.gender = gender;
            this.folder = folder;
            this.spec = String.format(Locale.ROOT, "%s-%s-%s", language, country, name);
        }
    }

    // Registration order matters: the first voice of a language is its default.
    private static final VoiceDef[] ALL_VOICES = {
        new VoiceDef("guj", "IND", "guf_05044", "Female", "gu-in")
    };

    private interface Sink {
        boolean write(byte[] pcm, int length);
    }

    private final Context context;
    private final List<VoiceDef> voices = new ArrayList<VoiceDef>();

    private static final class Slot {
        final Object lock = new Object();
        AndroidComposerImpl composer;
        ParcelFileDescriptor pipeline;
        long initFailedAt;      // 0 = not failed
    }

    private final List<Slot> slots = new ArrayList<Slot>();   // same order as voices
    private final Object synthLock = new Object();

    private volatile VoiceDef currentVoice;
    private volatile int rate = 100;
    private volatile int pitch = 100;
    private volatile int volume = DEFAULT_VOLUME;
    private volatile boolean stopRequested;
    private final java.util.concurrent.atomic.AtomicBoolean warmingUp = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * @throws RuntimeException if the native library cannot be loaded (for example on a 32-bit
     *         device) or if the voice pack is not present in the assets.
     */
    public GoogleTtsEngine(Context context) {
        this.context = context.getApplicationContext();
        try {
            // Initializes the class, which loads libgoogle_speech_sbg_tts_jni.
            Class.forName(AndroidComposerImpl.class.getName());
        } catch (Throwable t) {
            throw new RuntimeException("Google TTS native library is not available: " + t, t);
        }
        for (VoiceDef def : ALL_VOICES)
            if (hasAssets(ASSET_ROOT + "/" + def.folder))
                voices.add(def);
        if (voices.isEmpty())
            throw new RuntimeException("No Google voice data found in assets/" + ASSET_ROOT);
        for (int i = 0; i < voices.size(); i++)
            slots.add(new Slot());
        currentVoice = voices.get(0);
    }

    // ------------------------------------------------------------------ TtsEngine

    @Override
    public List<String> getAvailableVoices() {
        List<String> result = new ArrayList<String>();
        for (VoiceDef def : voices)
            result.add(def.spec);
        return result;
    }

    @Override
    public String[] getLanguage() {
        VoiceDef def = currentVoice;
        return new String[] { def.language, def.country, def.name };
    }

    @Override
    public String getVoice(String lang, String country, String variant) {
        VoiceDef def = find(lang, country, variant);
        return (def != null) ? def.spec : null;
    }

    @Override
    public Speaker getSpeaker(String langVoiceSpec) {
        for (VoiceDef def : voices)
            if (def.spec.equals(langVoiceSpec))
                return new GoogleVoice(def.spec, def.gender, this);
        VoiceDef def = voices.get(0);
        return new GoogleVoice(def.spec, def.gender, this);
    }

    @Override
    public int loadLanguage(String lang, String country, String variant) {
        VoiceDef def = find(lang, country, variant);
        if (def == null)
            return TextToSpeech.LANG_NOT_SUPPORTED;
        currentVoice = def;
        if (TextUtils.isEmpty(country) || Utils.ANY_COUNTRY.equals(country) || !def.country.equals(country))
            return TextToSpeech.LANG_AVAILABLE;
        return def.name.equals(variant) ? TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE : TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    public int setAudioStream(int value) {
        return (value < 0) ? TextToSpeech.ERROR : TextToSpeech.SUCCESS;
    }

    @Override
    public int setVolume(int value) {
        volume = Math.max(0, value);
        return TextToSpeech.SUCCESS;
    }

    @Override
    public int setSpeechRate(int value) {
        rate = clamp(value, MIN_RATE, MAX_RATE);
        return TextToSpeech.SUCCESS;
    }

    @Override
    public int setPitch(int value) {
        pitch = clamp(value, MIN_PITCH, MAX_PITCH);
        return TextToSpeech.SUCCESS;
    }

    @Override
    public int synthesizeText(String text, SoundFormatAdapter callback) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignored) {
        }
        VoiceDef voice = currentVoice;
        Slot slot = prepare(voice);
        if (slot == null) {
            callback.error();
            return callback.done();
        }
        stopRequested = false;
        if (callback.start(AndroidComposerImpl.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
            // Loudness is applied to the samples here, the adapter does not change the level.
            callback.setFullGain();
            final SoundFormatAdapter sink = callback;
            try {
                synchronized (synthLock) {
                    boolean ok = synthesize(slot, voice, text, rate, pitch, volume, (pcm, length) -> deliver(sink, pcm, length));
                    if (!ok)
                        callback.error();
                }
            } catch (Throwable t) {
                Log.e(TAG, "synthesis failed", t);
                callback.error();
            }
        }
        return callback.done();
    }

    @Override
    public void stop() {
        stopRequested = true;
    }

    @Override
    public void destroy() {
        stopRequested = true;
        for (Slot slot : slots)
            synchronized (slot.lock) {
                release(slot);
            }
    }

    // ------------------------------------------------------------------ synthesis

    private boolean synthesize(Slot slot, VoiceDef voice, String text, int rateValue, int pitchValue, int volumeValue, Sink sink) {
        AndroidComposerImpl composer;
        synchronized (slot.lock) {
            composer = slot.composer;
        }
        if (composer == null)
            return false;
        float speed = Math.max(0.5f, Math.min(3.0f, rateValue / 100f));
        float tone = Math.max(0.5f, Math.min(2.0f, pitchValue / 100f));
        float gain = Math.max(0f, Math.min(1.25f, volumeValue / 100f));
        if (!composer.setupSession(buildMarkup(speed, tone, text), buildSession(voice)))
            return false;
        while (!stopRequested) {
            int status = composer.readBuffered();
            if (composer.hasAudio()) {
                byte[] audio = composer.takeAudio();
                applyGain(audio, gain);
                if (!sink.write(audio, audio.length)) {
                    composer.reset();
                    return true;
                }
            }
            if (status == AndroidComposerImpl.STATUS_AUDIO)
                continue;
            return status == AndroidComposerImpl.STATUS_DONE;
        }
        composer.reset();
        return true;
    }

    private boolean deliver(SoundFormatAdapter sink, byte[] pcm, int length) {
        if (stopRequested)
            return false;
        int max = sink.getMaxBufferSize() & ~1;
        if (max <= 0)
            max = AUDIO_CHUNK_SIZE;
        for (int offset = 0; offset < length; offset += max)
            if (sink.audioAvailable(pcm, offset, Math.min(max, length - offset)) != TextToSpeech.SUCCESS) {
                stopRequested = true;
                return false;
            }
        return true;
    }

    private static void applyGain(byte[] pcm, float gain) {
        if (gain == 1f)
            return;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int value = Math.max(-32768, Math.min(32767, Math.round(sample * gain)));
            pcm[i] = (byte) value;
            pcm[i + 1] = (byte) (value >> 8);
        }
    }

    // ------------------------------------------------------------------ protocol messages (serialized protobuf)

    /** MarkupMessage { 2: InnerMessage { 1: ContentBlock { 2: ContentDetails { 1: TextBlock { 2: text } } }, 2: DataInfo { 3: rate, 4: pitch } } } */
    private static byte[] buildMarkup(float speed, float tone, String text) {
        byte[] textBlock = stringField(2, text);
        byte[] details = lengthField(1, textBlock);
        byte[] block = lengthField(2, details);
        byte[] dataInfo = concat(
            lengthField(3, fixed32Field(1, speed)),
            lengthField(4, fixed32Field(2, tone)));
        byte[] inner = concat(lengthField(1, block), lengthField(2, dataInfo));
        return lengthField(2, inner);
    }

    /** SessionMessage { 2: true, 3: 512, 14: VoiceConfig { 1: speaker, 2: gender }, 17: FeatureConfig } */
    private static byte[] buildSession(VoiceDef voice) {
        byte[] voiceConfig = concat(
            stringField(1, voice.name),
            stringField(2, "male".equalsIgnoreCase(voice.gender) ? "male" : "female"));
        ByteArrayOutputStream features = new ByteArrayOutputStream();
        for (String name : FEATURES_OFF)
            write(features, lengthField(1, concat(stringField(1, name), varintField(2, 0))));
        for (String name : FEATURES_ON)
            write(features, lengthField(1, concat(stringField(1, name), varintField(2, 1))));
        for (String name : FEATURES_INT)
            write(features, lengthField(2, concat(stringField(1, name), varintField(2, 1))));
        return concat(
            varintField(2, 1),
            varintField(3, 512),
            lengthField(14, voiceConfig),
            lengthField(17, features.toByteArray()));
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
        return out.toByteArray();
    }

    private static byte[] varintField(int number, long value) {
        return concat(varint(number << 3), varint(value));
    }

    private static byte[] lengthField(int number, byte[] payload) {
        return concat(varint((number << 3) | 2), varint(payload.length), payload);
    }

    private static byte[] stringField(int number, String value) {
        return lengthField(number, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] fixed32Field(int number, float value) {
        byte[] bits = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array();
        return concat(varint((number << 3) | 5), bits);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts)
            write(out, part);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }

    // ------------------------------------------------------------------ voice lookup

    private VoiceDef find(String lang, String country, String variant) {
        if (TextUtils.isEmpty(lang))
            return null;
        String c = Utils.ANY_COUNTRY.equals(country) ? null : country;
        VoiceDef languageDefault = null;
        VoiceDef countryDefault = null;
        for (VoiceDef v : voices) {
            if (!v.language.equals(lang))
                continue;
            if (languageDefault == null)
                languageDefault = v;
            if (!TextUtils.isEmpty(c) && v.country.equals(c)) {
                if (v.name.equals(variant))
                    return v;
                if (countryDefault == null)
                    countryDefault = v;
            }
        }
        return (countryDefault != null) ? countryDefault : languageDefault;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++)
            result[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return result;
    }

    // ------------------------------------------------------------------ native engine bootstrap

    /** Makes sure the native engine of the voice is running. */
    private Slot prepare(VoiceDef voice) {
        Slot slot = slots.get(Math.max(voices.indexOf(voice), 0));
        synchronized (slot.lock) {
            if (slot.composer != null)
                return slot;
            if ((slot.initFailedAt != 0) && (System.currentTimeMillis() - slot.initFailedAt < 8000))
                return null;
            File base = unpack(voice);
            if (base != null) {
                File[] dirs = base.listFiles();
                if (dirs != null) {
                    Arrays.sort(dirs);
                    Pattern speaker = Pattern.compile("speaker:\\s{0,5}\"" + Pattern.quote(voice.name) + "\"");
                    for (File dir : dirs) {
                        File pipeline = new File(dir, "pipeline.pb");
                        File description = new File(dir, "backend_pipeline.textproto");
                        if (!dir.isDirectory() || !pipeline.isFile() || !description.isFile())
                            continue;
                        if (!speaker.matcher(readText(description)).find())
                            continue;
                        if (tryInitialize(slot, dir, pipeline))
                            return slot;
                    }
                }
            }
            slot.initFailedAt = System.currentTimeMillis();
            return null;
        }
    }

    private boolean tryInitialize(Slot slot, File dir, File pipeline) {
        ParcelFileDescriptor descriptor = null;
        AndroidComposerImpl composer = null;
        try {
            descriptor = ParcelFileDescriptor.open(pipeline, ParcelFileDescriptor.MODE_READ_ONLY);
            composer = new AndroidComposerImpl(context, new AndroidDecoder());
            if (composer.init(descriptor, dir.getAbsolutePath(), ENGINE_CONFIG)) {
                slot.composer = composer;
                slot.pipeline = descriptor;
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "init of " + dir + " failed", t);
        }
        if (composer != null)
            composer.release();
        closeQuietly(descriptor);
        return false;
    }

    private void release(Slot slot) {
        if (slot.composer != null)
            slot.composer.release();
        closeQuietly(slot.pipeline);
        slot.composer = null;
        slot.pipeline = null;
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor != null)
            try {
                descriptor.close();
            } catch (Exception ignored) {
            }
    }

    private static String readText(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0)
                out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------ voice pack (assets -> files)

    private boolean hasAssets(String path) {
        try {
            String[] list = context.getAssets().list(path);
            return (list != null) && (list.length > 0);
        } catch (Exception ex) {
            return false;
        }
    }

    /** Copies assets/google/<folder> to filesDir/google/<folder> (once per installed app version). */
    private File unpack(VoiceDef voice) {
        File target = new File(new File(context.getFilesDir(), ASSET_ROOT), voice.folder);
        File marker = new File(target, READY_MARKER);
        String version = String.valueOf(installTime());
        if (marker.isFile() && version.equals(readText(marker).trim()))
            return target;
        try {
            if (!copyAssets(context.getAssets(), ASSET_ROOT + "/" + voice.folder, target))
                return null;
            try (OutputStream out = new FileOutputStream(marker)) {
                out.write(version.getBytes(StandardCharsets.UTF_8));
            }
            return target;
        } catch (Exception ex) {
            Log.e(TAG, "cannot unpack the voice pack", ex);
            return null;
        }
    }

    private long installTime() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static boolean copyAssets(AssetManager assets, String path, File target) throws Exception {
        String[] children = assets.list(path);
        if ((children != null) && (children.length > 0)) {
            if (!target.isDirectory() && !target.mkdirs())
                return false;
            for (String child : children)
                if (!copyAssets(assets, path + "/" + child, new File(target, child)))
                    return false;
            return true;
        }
        try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0)
                out.write(buffer, 0, n);
        }
        return true;
    }

    // ------------------------------------------------------------------ warm-up

    /** Starts the engine in the background so the first request does not wait for it. */
    public void warmUp() {
        if (!warmingUp.compareAndSet(false, true))
            return;
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                for (VoiceDef def : voices) {
                    Slot slot = prepare(def);
                    if (slot == null)
                        break;
                    synchronized (synthLock) {
                        stopRequested = false;
                        synthesize(slot, def, "\u0AA8\u0AAE\u0AB8\u0ACD\u0AA4\u0AC7", 100, 100, 0, (pcm, length) -> true);
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "google-warmup");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ self test (settings "Test" button)

    /** Speaks a sample through the speaker and returns a report. Blocking: call from a background thread. */
    public String selfTest(String voiceSpec, String text, int rateValue, int pitchValue, int volumeValue) {
        for (Slot slot : slots)
            synchronized (slot.lock) {
                slot.initFailedAt = 0;
            }
        VoiceDef voice = voices.get(0);
        for (VoiceDef d : voices)
            if (d.spec.equals(voiceSpec))
                voice = d;
        StringBuilder report = new StringBuilder();
        try {
            Slot slot = prepare(voice);
            if (slot == null) {
                report.append("FAILED: engine/voice could not be started.\n\n");
            } else {
                final ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
                synchronized (synthLock) {
                    stopRequested = false;
                    boolean ok = synthesize(slot, voice, text, clamp(rateValue, MIN_RATE, MAX_RATE),
                        clamp(pitchValue, MIN_PITCH, MAX_PITCH), volumeValue, (pcm, length) -> {
                            pcmOut.write(pcm, 0, length);
                            return true;
                        });
                    if (!ok)
                        report.append("FAILED: synthesis returned an error.\n");
                }
                byte[] audio = pcmOut.toByteArray();
                int hz = AndroidComposerImpl.SAMPLE_RATE;
                report.append("Audio produced: ").append(audio.length / 2).append(" samples @ ").append(hz).append(" Hz\n");
                if (audio.length < 2000) {
                    report.append("FAILED: the engine produced (almost) no audio.\n\n");
                } else {
                    report.append("OK - playing...\n\n");
                    android.media.AudioTrack track = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC, hz, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(audio.length, 4096),
                        android.media.AudioTrack.MODE_STATIC);
                    track.write(audio, 0, audio.length);
                    track.play();
                    long end = System.currentTimeMillis() + (audio.length / 2L) * 1000L / hz + 500;
                    while (System.currentTimeMillis() < end)
                        try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                    track.release();
                }
            }
        } catch (Throwable t) {
            report.append("FAILED: ").append(t).append("\n\n");
        }
        return report.toString();
    }

}
