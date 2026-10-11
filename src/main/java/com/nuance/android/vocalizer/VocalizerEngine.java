package com.nuance.android.vocalizer;

import java.io.IOException;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;

import com.nuance.android.vocalizer.internal.VocalizerFileInfo;
import com.nuance.android.vocalizer.internal.VocalizerResourceInfo;
import com.nuance.android.vocalizer.internal.VocalizerStatusInfo;

/**
 * Thin Java side of libNuanceVocalizer.so (arm64-v8a).
 *
 * The package, class name, native method names, the mNativeEnginePtr field and the four callbacks
 * (audioSamplesReceived, speechMarksReceived, openAssetFile, closeAssetFile) are all dictated by the
 * native library: it looks them up by name through JNI. Do not rename anything in this class.
 * The R8/ProGuard rules in proguard-project.txt keep everything in this package.
 *
 * Calling convention (verified from the library): every callback is invoked synchronously on the
 * thread that called speakNative()/initializeNative(), never from a native thread.
 */
public class VocalizerEngine {

    /** Receives synthesized audio. */
    public interface Listener {
        /** @return true to continue synthesis, false to abort it. */
        boolean onAudio(short[] samples);
    }

    private static final String TAG = "SmartVoice-Nuance";

    // Examined by JNI_OnLoad (GetFieldID "mNativeEnginePtr", "J"). Written by createNativeEngine().
    @SuppressWarnings("unused")
    private long mNativeEnginePtr;

    private final AssetManager assets;
    private final Context appContext;
    private List<String> assetIndex;
    private final String assetRoot;
    private final SparseArray<Closeable> openDescriptors = new SparseArray<Closeable>();
    private volatile Listener listener;
    private boolean destroyed;

    static {
        // libnofdsan switches off Android's fdsan abort so that the engine can close the
        // duplicated asset descriptors it owns. android_fdsan_set_error_level() exists since API 29.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                System.loadLibrary("nofdsan");
            } catch (Throwable t) {
            }
        }
        System.loadLibrary("NuanceVocalizer");
    }

    /**
     * @param assetRoot folder inside the APK assets that holds the .dat/.hdr voice files.
     *                  Files requested by the engine are searched there as well.
     */
    public VocalizerEngine(Context context, String assetRoot) {
        this.assets = context.getAssets();
        this.appContext = context.getApplicationContext();
        this.assetRoot = assetRoot;
        createNativeEngine();
    }

    // ------------------------------------------------------------------ public API

    public boolean initialize(String path) {
        return initializeNative(path);
    }

    public VocalizerVoice[] getAvailableVoices() {
        VocalizerVoice[] voices = getAvailableVoiceListNative();
        return (voices != null) ? voices : new VocalizerVoice[0];
    }

    public boolean loadVoice(VocalizerVoice voice) {
        return loadVoiceNative(voice);
    }

    /** Blocks until the whole text has been synthesized or stop() is called. */
    public void speak(String text, Listener audioListener) {
        listener = audioListener;
        try {
            speakNative(text);
        } finally {
            listener = null;
        }
    }

    public void stop() {
        stopSpeechNative();
    }

    public synchronized void release() {
        if (destroyed)
            return;
        destroyed = true;
        try {
            releaseNative();
        } catch (Throwable t) {
        }
        try {
            destroyNativeEngine();
        } catch (Throwable t) {
        }
        synchronized (openDescriptors) {
            for (int i = 0; i < openDescriptors.size(); i++)
                closeQuietly(openDescriptors.valueAt(i));
            openDescriptors.clear();
        }
    }

    // ------------------------------------------------------------------ natives (names fixed by the library)

    private native void createNativeEngine();

    private native void destroyNativeEngine();

    private native boolean initializeNative(String path);

    private native void releaseNative();

    private native VocalizerVoice[] getAvailableVoiceListNative();

    private native boolean loadVoiceNative(VocalizerVoice voice);

    private native void speakNative(String text);

    private native void stopSpeechNative();

    public native boolean setAudioBufferSize(int size);

    public native void setEnableMarks(boolean enable);

    public native void setVolume(int value);

    public native int getVolume();

    public native void setRate(int value);

    public native int getRate();

    public native void setPitch(int value);

    public native int getPitch();

    public native void setWaitFactor(int value);

    public native int getWaitFactor();

    public native void setReadMode(int value);

    public native int getReadMode();

    public native void setTextMode(int value);

    public native int getTextMode();

    public native void setTextFormat(int value);

    public native int getTextFormat();

    public native VocalizerResourceInfo nativeLoadResource(byte[] data, String mimeType);

    public native void nativeUnloadResource(VocalizerResourceInfo resource);

    public native VocalizerStatusInfo getStatInfo();

    public native VocalizerVersion getEngineVersion();

    // ------------------------------------------------------------------ callbacks (called from native code)

    /** Audio chunk, 16-bit mono PCM. Returning false aborts synthesis (verified from the library code). */
    @SuppressWarnings("unused")
    private boolean audioSamplesReceived(short[] samples) {
        Listener l = listener;
        if (l == null)
            return false;
        try {
            return l.onAudio(samples);
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private void speechMarksReceived(VocalizerSpeechMark[] marks) {
        // Speech marks are not used.
    }

    /**
     * The engine treats every relative file name as an asset. We return a descriptor of the
     * uncompressed asset; the native side duplicates it and seeks to startOffset.
     */
    @SuppressWarnings("unused")
    private VocalizerFileInfo openAssetFile(String name) {
        if (name == null)
            return null;
        String path = resolveAsset(name);
        if (path == null) {
            return null;
        }
        try {
            try {
                AssetFileDescriptor afd = assets.openFd(path);
                int fd = afd.getParcelFileDescriptor().getFd();
                synchronized (openDescriptors) {
                    openDescriptors.put(fd, afd);
                }
                return new VocalizerFileInfo(fd, afd.getStartOffset(), afd.getLength());
            } catch (IOException compressed) {
                // asset is stored compressed in the APK: unpack it once and hand out a normal file
                File f = extract(path);
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                int fd = pfd.getFd();
                synchronized (openDescriptors) {
                    openDescriptors.put(fd, pfd);
                }
                return new VocalizerFileInfo(fd, 0, f.length());
            }
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private void closeAssetFile(int descriptor) {
        Closeable c;
        synchronized (openDescriptors) {
            c = openDescriptors.get(descriptor);
            openDescriptors.remove(descriptor);
        }
        closeQuietly(c);
    }

    // ------------------------------------------------------------------ asset name resolution

    private File extract(String path) throws IOException {
        File dir = new File(appContext.getFilesDir(), "vocalizer_cache");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("cannot create " + dir);
        File out = new File(dir, path.replace('/', '_'));
        long expected = -1;
        try (InputStream in = assets.open(path)) {
            if (out.isFile()) {
                long n = 0; byte[] b = new byte[65536]; int r;
                while ((r = in.read(b)) > 0) n += r;
                expected = n;
            }
        }
        if (out.isFile() && out.length() == expected && expected > 0)
            return out;
        File tmp = new File(dir, out.getName() + ".tmp");
        try (InputStream in = assets.open(path); OutputStream os = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536]; int r;
            while ((r = in.read(b)) > 0) os.write(b, 0, r);
        }
        if (!tmp.renameTo(out)) {
            out.delete();
            if (!tmp.renameTo(out))
                throw new IOException("cannot store " + out);
        }
        return out;
    }

    private static String stem(String file) {
        int dot = file.lastIndexOf('.');
        return (dot > 0) ? file.substring(0, dot) : file;
    }

    /** letters and digits split into separate lowercase tokens: "bet3f22" -> bet,3,f,22 */
    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int kind = 0;
        for (int i = 0; i <= s.length(); i++) {
            char c = (i < s.length()) ? Character.toLowerCase(s.charAt(i)) : ' ';
            int k = Character.isLetter(c) ? 1 : (Character.isDigit(c) ? 2 : 0);
            if (k != kind && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (k != 0)
                cur.append(c);
            kind = k;
        }
        return out;
    }

    private static int extRank(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        if (f.endsWith(".dat")) return 0;
        if (f.endsWith(".xmf")) return 1;
        if (f.endsWith(".hdr")) return 3;
        return 2;
    }

    private synchronized List<String> assetFiles() {
        if (assetIndex == null) {
            assetIndex = new ArrayList<String>();
            try {
                String[] l = assets.list(assetRoot == null ? "" : assetRoot);
                if (l != null)
                    for (String f : l)
                        assetIndex.add(f);
            } catch (IOException ex) {
            }
        }
        return assetIndex;
    }

    private String full(String file) {
        return (assetRoot == null || assetRoot.isEmpty()) ? file : assetRoot + "/" + file;
    }

    /** Maps a name requested by the engine to an asset path (null if nothing fits). */
    private final java.util.concurrent.ConcurrentHashMap<String, String> resolved = new java.util.concurrent.ConcurrentHashMap<String, String>();

    private String resolveAsset(String name) {
        String hit = resolved.get(name);
        if (hit != null)
            return hit;
        hit = resolveAsset0(name);
        if (hit != null)
            resolved.put(name, hit);
        return hit;
    }

    private String resolveAsset0(String name) {
        int colon = name.indexOf(':');
        String n = (colon > 0) ? name.substring(0, colon) : name;
        List<String> files = assetFiles();
        // 1. exact / flat names
        for (String c : candidatePaths(n)) {
            for (String f : files)
                if (full(f).equals(c) || f.equals(c))
                    return full(f);
        }
        // 2. same name, other extension (sysdct -> sysdct.xmf)
        String flat = stem(n.replace('/', '_')).toLowerCase(Locale.ROOT);
        String best = null;
        for (String f : files)
            if (stem(f).toLowerCase(Locale.ROOT).equals(flat) && (best == null || extRank(f) < extRank(best)))
                best = f;
        if (best != null)
            return full(best);
        // 3. token matching
        List<String> req = tokens(n);
        if (req.isEmpty())
            return null;
        String first = req.get(0);
        Set<String> vocab = new HashSet<String>();
        for (String f : files)
            vocab.addAll(tokens(stem(f)));
        List<String> cands = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (String f : files) {
            List<String> t = tokens(stem(f));
            if (!t.isEmpty() && t.get(0).equals(first) && seen.add(stem(f).toLowerCase(Locale.ROOT)))
                cands.add(f);
        }
        if (cands.isEmpty())
            return null;
        String pick = null;
        int bestScore = Integer.MIN_VALUE;
        for (String f : cands) {
            List<String> t = tokens(stem(f));
            int matched = 0, penalty = 0;
            for (int i = 1; i < req.size(); i++) {
                String r = req.get(i);
                if (t.contains(r)) matched++;
                else if (vocab.contains(r) && r.length() > 2) penalty++;
            }
            if (cands.size() > 1 && (matched == 0 || penalty > 0))
                continue;
            int score = matched * 10 - penalty * 20 - (t.size() - 1 - matched);
            if (score > bestScore) { bestScore = score; pick = f; }
        }
        if (pick == null && cands.size() == 1)
            pick = cands.get(0);
        if (pick == null)
            return null;
        for (String f : files)
            if (stem(f).equalsIgnoreCase(stem(pick)) && extRank(f) < extRank(pick))
                pick = f;
        return full(pick);
    }

    // ------------------------------------------------------------------ helpers

    private String[] candidatePaths(String name) {
        String clean = name;
        while (clean.startsWith("./"))
            clean = clean.substring(2);
        while (clean.startsWith("/"))
            clean = clean.substring(1);
        String base = clean.substring(clean.lastIndexOf('/') + 1);
        String flat = clean.replace('/', '_');
        List<String> out = new ArrayList<String>();
        for (String x : new String[] { clean, base, flat })
            for (String ext : new String[] { "", ".dat", ".xmf" }) {
                out.add(x + ext);
                if (assetRoot != null && !assetRoot.isEmpty())
                    out.add(assetRoot + "/" + x + ext);
            }
        return out.toArray(new String[0]);
    }

    private static void closeQuietly(Closeable afd) {
        if (afd != null)
            try {
                afd.close();
            } catch (IOException ex) {
                // nothing to do
            }
    }

}
