package aktech.tts.synth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.content.Context;
import android.media.AudioFormat;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;

import com.nuance.android.vocalizer.VocalizerEngine;
import com.nuance.android.vocalizer.VocalizerVoice;
import com.nuance.android.vocalizer.internal.VocalizerResourceInfo;

import aktech.tts.smartvoice.api.SoundFormatAdapter;
import aktech.tts.smartvoice.api.Speaker;
import aktech.tts.smartvoice.api.TtsEngine;
import aktech.tts.smartvoice.api.Utils;

/**
 * Nuance Vocalizer (bet3, 22 kHz; .hdr headers + .dat data) voices for SmartVoice:
 *
 *   eng-USA-Samantha   American English, female
 *   hin-IND-Lekha      Hindi, female
 *
 * Every voice is a stand-alone "general voice" for SmartVoice (no apostrophe variants), so the voice
 * name is unique and shows up in the voice assignment screens as "Samantha - English (United States)" etc.
 * The first voice registered for a language becomes its default (Samantha for English, Lekha for Hindi).
 *
 * The native engine is created lazily on the first synthesis request (never on the UI thread) because
 * initialization reads several megabytes of voice data.
 */
public class NuanceTtsEngine implements TtsEngine {

    private static final String TAG = "SmartVoice-Nuance";
    private static final String ASSET_ROOT = "vocalizer";

    private static final int DEFAULT_SAMPLE_RATE = 22050;
    private static final int AUDIO_BUFFER_SIZE = 2048;
    private static final int DEFAULT_VOLUME = 80;

    private static final int MIN_RATE = 50;
    private static final int MAX_RATE = 400;
    private static final int MIN_PITCH = 50;
    private static final int MAX_PITCH = 200;

    /**
     * UNVERIFIED guess, used only when the engine initializes without reporting any voices.
     * A wrong value is harmless: the engine answers with an error that is written to logcat.
     */
    private static final String[] PIPELINE_MIME_GUESSES = {
        "application/x-vocalizer-rsc-text;loader=global"
    };

    private static final class VoiceDef {
        final String language;   // ISO 639-2 (as used by SmartVoice)
        final String country;    // ISO 3166 alpha-3
        final String name;
        final String gender;
        final String dataFile;
        final String pipelineFile;
        final String spec;

        VoiceDef(String language, String country, String name, String gender, String dataFile, String pipelineFile) {
            this.language = language;
            this.country = country;
            this.name = name;
            this.gender = gender;
            this.dataFile = dataFile;
            this.pipelineFile = pipelineFile;
            this.spec = String.format(Locale.ROOT, "%s-%s-%s", language, country, name);
        }
    }

    // Registration order matters: the first voice of a language is its default.
    private static final VoiceDef[] ALL_VOICES = {
        new VoiceDef("eng", "USA", "Samantha", "Female",
                     "select_samantha_bet3f22.dat",
                     "ve_pipeline_enu_samantha_22_bet3.hdr"),
        new VoiceDef("hin", "IND", "Lekha", "Female",
                     "select_lekha_bet3f22.dat",
                     "ve_pipeline_hii_lekha_22_bet3.hdr")
    };

    private final Context context;
    private final List<VoiceDef> voices = new ArrayList<VoiceDef>();

    /** One native engine per voice: both voices stay loaded, so switching language costs nothing. */
    private static final class Slot {
        final Object lock = new Object();
        VocalizerEngine engine;
        VocalizerVoice[] nativeVoices;
        VoiceDef loadedVoice;
        int sampleRate = DEFAULT_SAMPLE_RATE;
        long initFailedAt;                          // 0 = not failed
        int appliedRate = -1, appliedPitch = -1, appliedVolume = -1;   // what the native engine currently has
    }

    private final List<Slot> slots = new ArrayList<Slot>();   // same order as voices
    private volatile Slot activeSlot;
    private static final String OPERATING_POINT = "embedded-high";
    private final Object synthLock = new Object();

    private volatile VoiceDef currentVoice;
    private volatile int rate = 100;
    private volatile int pitch = 100;
    private volatile int volume = DEFAULT_VOLUME;
    private volatile boolean speaking;
    private volatile boolean stopRequested;

    private byte[] pcm = new byte[0];


    /**
     * @throws RuntimeException if the native library cannot be loaded (for example on a 32-bit
     *         device) or if no voice data is present in the assets.
     */
    public NuanceTtsEngine(Context context) {
        this.context = context.getApplicationContext();
        try {
            // Initializes the class, which loads libnofdsan / libNuanceVocalizer.
            Class.forName(VocalizerEngine.class.getName());
        } catch (Throwable t) {
            throw new RuntimeException("Nuance Vocalizer native library is not available: " + t, t);
        }
        Set<String> present = new HashSet<String>();
        try {
            String[] files = this.context.getAssets().list(ASSET_ROOT);
            if (files != null)
                Collections.addAll(present, files);
        } catch (Exception ex) {
            throw new RuntimeException("Cannot list the Nuance voice data", ex);
        }
        for (VoiceDef def : ALL_VOICES)
            if (present.contains(def.dataFile))
                voices.add(def);
        if (voices.isEmpty())
            throw new RuntimeException("No Nuance voice data found in assets/" + ASSET_ROOT);
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
                return new NuanceVoice(def.spec, def.gender, this);
        VoiceDef def = voices.get(0);
        return new NuanceVoice(def.spec, def.gender, this);
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
        VocalizerEngine e = slot.engine;
        int rateHz;
        synchronized (slot.lock) {
            rateHz = slot.sampleRate;
        }
        stopRequested = false;
        if (callback.start(rateHz, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
            // Loudness is the engine's own volume (0-100), the adapter does not change the level.
            callback.setFullGain();
            try {
                synchronized (synthLock) {
                    int nativeVolume = Math.max(0, Math.min(100, volume));
                    if (slot.appliedVolume != nativeVolume) {
                        e.setVolume(nativeVolume);
                        slot.appliedVolume = nativeVolume;
                    }
                    if (slot.appliedRate != rate) {
                        e.setRate(rate);
                        slot.appliedRate = rate;
                    }
                    if (slot.appliedPitch != pitch) {
                        e.setPitch(pitch);
                        slot.appliedPitch = pitch;
                    }
                    speaking = true;
                    activeSlot = slot;
                    final SoundFormatAdapter sink = callback;
                    e.speak(text, samples -> deliver(sink, samples));
                }
            } catch (Throwable t) {
                callback.error();
            } finally {
                speaking = false;
            }
        }
        return callback.done();
    }

    @Override
    public void stop() {
        stopRequested = true;
        Slot slot = activeSlot;
        VocalizerEngine e = null;
        if (slot != null)
            synchronized (slot.lock) {
                e = slot.engine;
            }
        if ((e != null) && speaking)
            try {
                e.stop();
            } catch (Throwable t) {
            }
    }

    @Override
    public void destroy() {
        for (Slot slot : slots) {
            VocalizerEngine e;
            synchronized (slot.lock) {
                e = slot.engine;
                slot.engine = null;
                slot.loadedVoice = null;
                slot.nativeVoices = null;
            }
            if (e != null)
                e.release();
        }
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


    // ------------------------------------------------------------------ audio

    /** Hands a block of engine audio straight to the system, on the engine's own thread. */
    private boolean deliver(SoundFormatAdapter sink, short[] samples) {
        if (stopRequested || (samples == null))
            return false;
        int length = samples.length * 2;
        if (length == 0)
            return true;
        if (pcm.length < length)
            pcm = new byte[length];
        ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.nativeOrder()).asShortBuffer().put(samples);
        int max = sink.getMaxBufferSize() & ~1;
        if (max <= 0)
            max = AUDIO_BUFFER_SIZE;
        for (int offset = 0; offset < length; offset += max)
            if (sink.audioAvailable(pcm, offset, Math.min(max, length - offset)) != TextToSpeech.SUCCESS) {
                stopRequested = true;
                return false;
            }
        return true;
    }

    // ------------------------------------------------------------------ native engine bootstrap

    /** Makes sure the engine of the voice is running and the voice is loaded. */
    private Slot prepare(VoiceDef voice) {
        int index = voices.indexOf(voice);
        Slot own = slots.get(Math.max(index, 0));
        Slot slot = (ensureEngine(own) != null) ? own : null;
        if (slot == null)
            for (Slot other : slots)
                if ((other != own) && (ensureEngine(other) != null)) {
                    slot = other;     // fallback: one shared engine that switches voices
                    break;
                }
        if (slot == null)
            return null;
        VocalizerEngine e = slot.engine;
        synchronized (slot.lock) {
            if (slot.loadedVoice == voice)
                return slot;
            VocalizerVoice nativeVoice = findNativeVoice(slot, voice);
            if (nativeVoice == null) {
                return null;
            }
            boolean loaded;
            try {
                loaded = e.loadVoice(nativeVoice);
            } catch (Throwable t) {
                loaded = false;
            }
            if (!loaded)
                return null;
            e.setAudioBufferSize(AUDIO_BUFFER_SIZE);
            slot.loadedVoice = voice;
            slot.appliedRate = slot.appliedPitch = slot.appliedVolume = -1;
            int sr = nativeVoice.mSampleRate;
            if (sr > 0 && sr < 1000)
                sr = (sr == 22) ? 22050 : (sr == 11) ? 11025 : (sr == 44) ? 44100 : (sr == 8) ? 8000 : (sr == 16) ? 16000 : sr * 1000; // engine reports kHz
            slot.sampleRate = (sr > 0) ? sr : DEFAULT_SAMPLE_RATE;
            return slot;
        }
    }

    private VocalizerVoice findNativeVoice(Slot slot, VoiceDef voice) {
        if (slot.nativeVoices == null)
            return null;
        for (VocalizerVoice v : slot.nativeVoices)
            if ((v.mVoiceName != null) && v.mVoiceName.equalsIgnoreCase(voice.name))
                return v;
        for (VocalizerVoice v : slot.nativeVoices)
            if ((v.mVoiceName != null) && v.mVoiceName.toLowerCase(Locale.ROOT).contains(voice.name.toLowerCase(Locale.ROOT)))
                return v;
        return null;
    }

    private VocalizerEngine ensureEngine(Slot slot) {
        synchronized (slot.lock) {
            if (slot.engine != null)
                return slot.engine;
            if ((slot.initFailedAt != 0) && (System.currentTimeMillis() - slot.initFailedAt < 8000))
                return null;

            // 0. The engine wants the pipeline header XML itself (not a folder name):
            //    all voices in one buffer, then every voice alone. Data files are fetched via openAssetFile.
            String all = buildHeaderText(voices);
            if ((all != null) && tryInitialize(slot, "headers-all", all))
                return slot.engine;
            if (voices.size() > 1)
                for (VoiceDef def : voices) {
                    List<VoiceDef> one = new ArrayList<VoiceDef>();
                    one.add(def);
                    String h = buildHeaderText(one);
                    if ((h != null) && tryInitialize(slot, "headers-" + def.name, h))
                        return slot.engine;
                }

            // 1. Legacy attempts (folder names).
            String[][] attempts = {
                { "assets", ASSET_ROOT },
                { "assets/", ASSET_ROOT + "/" }
            };
            for (String[] attempt : attempts)
                if (tryInitialize(slot, attempt[0], attempt[1]))
                    return slot.engine;

            // 2. Voice data unpacked to the private files directory (absolute names go through fopen).
            File directory = unpackVoiceData();
            if (directory != null) {
                String path = directory.getAbsolutePath();
                String[][] unpacked = {
                    { "files", path },
                    { "files/", path + "/" }
                };
                for (String[] attempt : unpacked)
                    if (tryInitialize(slot, attempt[0], attempt[1]))
                        return slot.engine;
            }

            slot.initFailedAt = System.currentTimeMillis();
            return null;
        }
    }

    private boolean tryInitialize(Slot slot, String label, String path) {
        VocalizerEngine candidate = null;
        try {
            candidate = new VocalizerEngine(context, ASSET_ROOT);
            boolean ok = candidate.initialize(path);
            if (ok) {
                VocalizerVoice[] list = candidate.getAvailableVoices();
                if ((list.length == 0) && loadPipelineHeaders(candidate))
                    list = candidate.getAvailableVoices();
                if (list.length > 0) {
                    slot.engine = candidate;
                    slot.nativeVoices = list;
                    slot.loadedVoice = null;
                    return true;
                }
            }
        } catch (Throwable t) {
        }
        if (candidate != null)
            candidate.release();
        return false;
    }

    /** Fallback for engines that need the pipeline headers to be handed over explicitly. */
    private boolean loadPipelineHeaders(VocalizerEngine candidate) {
        boolean any = false;
        for (VoiceDef def : voices) {
            byte[] data = readAsset(ASSET_ROOT + "/" + def.pipelineFile);
            if (data == null)
                continue;
            for (String mime : PIPELINE_MIME_GUESSES) {
                try {
                    VocalizerResourceInfo info = candidate.nativeLoadResource(data, mime);
                    boolean loaded = (info != null) && (info.mHandle != 0);
                    if (loaded) {
                        any = true;
                        break;
                    }
                } catch (Throwable t) {
                }
            }
        }
        return any;
    }

    private byte[] readAsset(String path) {
        try (InputStream in = context.getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0)
                out.write(buffer, 0, n);
            return out.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Copies assets/vocalizer to filesDir/vocalizer (only what is missing or has a different size). */
    private File unpackVoiceData() {
        File directory = new File(context.getFilesDir(), ASSET_ROOT);
        if (!directory.isDirectory() && !directory.mkdirs())
            return null;
        try {
            String[] files = context.getAssets().list(ASSET_ROOT);
            if (files == null)
                return null;
            for (String name : files) {
                File target = new File(directory, name);
                long expected = -1;
                try (android.content.res.AssetFileDescriptor afd = context.getAssets().openFd(ASSET_ROOT + "/" + name)) {
                    expected = afd.getLength();
                } catch (Exception ex) {
                    // size unknown, copy anyway
                }
                if (target.isFile() && (target.length() == expected))
                    continue;
                try (InputStream in = context.getAssets().open(ASSET_ROOT + "/" + name);
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) > 0)
                        out.write(buffer, 0, n);
                }
            }
            return directory;
        } catch (Exception ex) {
            return null;
        }
    }


    /** Concatenated pipeline headers, each starting with its own "<?xml ...?>" line. */
    private String buildHeaderText(List<VoiceDef> list) {
        StringBuilder sb = new StringBuilder();
        for (VoiceDef def : list) {
            byte[] data = readAsset(ASSET_ROOT + "/" + def.pipelineFile);
            if (data == null)
                continue;
            String t = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            if (t.length() > 0 && t.charAt(0) == '\uFEFF')
                t = t.substring(1);
            t = t.trim();
            int x = t.indexOf("<?xml");
            if (x > 0)
                t = t.substring(x);
            if (!t.startsWith("<?xml"))
                t = "<?xml version=\"1.0\"?>\n" + t;
            // The voice data (select = FIX3, synth = fixed point) belongs to the fixed-point engine. The engine only
            // picks select/synth "bet3fxd" when the pipeline name carries an operating point.
            if (!t.contains("<voiceoperatingpoint>")) {
                // with an operating point the engine composes pipeline/<lang>/<voice>/<kHz>/<op>/text/pcm
                t = t.replaceFirst("(<BROKERSTRING>pipeline/[^<]*?/\\d+)/bet3(/text/pcm</BROKERSTRING>)",
                    "$1/" + OPERATING_POINT + "$2");
                t = t.replaceFirst("<voicemodel>",
                    "<voiceoperatingpoint>" + OPERATING_POINT + "</voiceoperatingpoint>\n    <voicemodel>");
            }
            sb.append(t).append('\n');
        }
        return (sb.length() == 0) ? null : sb.toString();
    }

    // ------------------------------------------------------------------ warm-up (zero start-up latency)

    private final java.util.concurrent.atomic.AtomicBoolean warmingUp = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Starts the engine in the background and runs a tiny silent synthesis for every voice, so the first real
     * request does not pay for engine start, data loading or cold caches. The voice of the device language is
     * warmed last and therefore stays loaded.
     */
    public void warmUp() {
        if (!warmingUp.compareAndSet(false, true))
            return;
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                final String lang = Locale.getDefault().getISO3Language();
                List<VoiceDef> order = new ArrayList<VoiceDef>(voices);
                Collections.sort(order, (a, b) -> Boolean.compare(a.spec.startsWith(lang), b.spec.startsWith(lang)));
                for (VoiceDef def : order) {
                    Slot slot = prepare(def);
                    if (slot == null)
                        break;
                    VocalizerEngine e = slot.engine;
                    synchronized (synthLock) {
                        e.setRate(100);
                        e.setPitch(100);
                        stopRequested = false;
                        speaking = true;
                        try {
                            e.speak(def.spec.startsWith("hin") ? "\u0928\u092e\u0938\u094d\u0924\u0947" : "Hello", samples -> true);
                        } finally {
                            speaking = false;
                            slot.appliedRate = slot.appliedPitch = slot.appliedVolume = -1;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "nuance-warmup");
        t.setDaemon(true);
        t.start();
    }


    // ------------------------------------------------------------------ self test (settings "Test" button)

    /**
     * Speaks a sample with the given settings straight through the speaker and returns a report.
     * Blocking: call from a background thread.
     */
    public String selfTest(String voiceSpec, String text, int rateValue, int pitchValue, int volumeValue) {
        for (Slot slot : slots)
            synchronized (slot.lock) {
                slot.initFailedAt = 0;
            }
        VoiceDef voice = null;
        for (VoiceDef d : voices)
            if (d.spec.equals(voiceSpec))
                voice = d;
        if (voice == null)
            voice = voices.get(0);
        StringBuilder report = new StringBuilder();
        try {
            Slot slot = prepare(voice);
            if (slot == null) {
                report.append("FAILED: engine/voice could not be started.\n\n");
            } else {
                VocalizerEngine e = slot.engine;
                final java.io.ByteArrayOutputStream pcmOut = new java.io.ByteArrayOutputStream();
                final float gain = 1f;
                int hz;
                synchronized (slot.lock) {
                    hz = slot.sampleRate;
                }
                synchronized (synthLock) {
                    e.setVolume(Math.max(0, Math.min(100, volumeValue)));
                    e.setRate(clamp(rateValue, MIN_RATE, MAX_RATE));
                    e.setPitch(clamp(pitchValue, MIN_PITCH, MAX_PITCH));
                    slot.appliedRate = slot.appliedPitch = slot.appliedVolume = -1;
                    stopRequested = false;
                    speaking = true;
                    try {
                        e.speak(text, samples -> {
                            for (short sm : samples) {
                                int v = Math.max(-32768, Math.min(32767, Math.round(sm * gain)));
                                pcmOut.write(v & 0xff);
                                pcmOut.write((v >> 8) & 0xff);
                            }
                            return true;
                        });
                    } finally {
                        speaking = false;
                    }
                }
                byte[] audio = pcmOut.toByteArray();
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
