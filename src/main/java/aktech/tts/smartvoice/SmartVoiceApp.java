package aktech.tts.smartvoice;

import static aktech.tts.smartvoice.api.Utils.ANY_COUNTRY;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import aktech.tts.smartvoice.api.TtsEngine;
import aktech.tts.smartvoice.api.Speaker;

import aktech.tts.smartvoice.markup.ExpirableHint;

import aktech.tts.synth.GoogleTtsEngine;
import aktech.tts.synth.NuanceTtsEngine;

public class SmartVoiceApp extends Application {

    /** Languages with automatic language switching until the user chooses otherwise. */
    public static final java.util.Set<String> DEFAULT_AUTO_LANGS =
        java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(java.util.Arrays.asList("eng", "hin", "guj")));


    public static final String LOG_TAG = "SmartVoice";

    public final Set<String> languages;
    public final Set<String> validVoices;
    public final Set<String> generalVoices;
    public final Map<String, Speaker> voices;
    public final Map<String, String> defaultVoices;
    public final ExpirableHint<String> explicitVoice;

    private Handler handler;
    private Runnable uiNotifier;
    private SharedPreferences preferences;

    private NuanceTtsEngine nuanceEngine;
    private GoogleTtsEngine googleEngine;


    public SmartVoiceApp() {
        uiNotifier = null;
        nuanceEngine = null;
        googleEngine = null;
        languages = new ConcurrentSkipListSet<String>();
        validVoices = new ConcurrentSkipListSet<String>();
        generalVoices = new ConcurrentSkipListSet<String>();
        voices = new ConcurrentHashMap<String, Speaker>();
        defaultVoices = new ConcurrentHashMap<String, String>();
        explicitVoice = new ExpirableHint<String>(60000);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(getMainLooper());
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = preferences.edit();
        refresh();
        String languageDetectionOrderKey = getString(R.string.language_detection_order_key);
        String currentOrder = preferences.getString(languageDetectionOrderKey, null);
        if (currentOrder != null) {
            LanguageDetectionOrder order = new LanguageDetectionOrder(this, currentOrder);
            String newOrder = order.getStringValue();
            if (newOrder == null) {
                editor.remove(languageDetectionOrderKey);
                editor.apply();
            } else if (!currentOrder.equals(newOrder)) {
                editor.putString(languageDetectionOrderKey, newOrder);
                editor.apply();
            }
        }
        checkPostNotificationsPermission();
    }

    public synchronized void refresh() {
        lockTtsService();
        if (nuanceEngine == null)
            try {
                nuanceEngine = new NuanceTtsEngine(this);
                nuanceEngine.warmUp();
            } catch (Throwable exception) {
                nuanceEngine = null;
            }
        if (googleEngine == null)
            try {
                googleEngine = new GoogleTtsEngine(this);
                googleEngine.warmUp();
            } catch (Throwable exception) {
                googleEngine = null;
            }
        languages.clear();
        voices.clear();
        defaultVoices.clear();
        generalVoices.clear();
        validVoices.clear();
        if (nuanceEngine != null)
            registerVoices(nuanceEngine);
        if (googleEngine != null)
            registerVoices(googleEngine);
        explicitVoice.clear();
        unlockTtsService();
        scheduleUiNotification();
    }

    public synchronized void setUiNotifier(Runnable action) {
        if (uiNotifier != null)
            handler.removeCallbacks(uiNotifier);
        uiNotifier = action;
    }

    public boolean isDebugEnabled() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 0;
    }

    public String getInternalVoiceName(String voice) {
        return voice.substring(8);
    }


    public NuanceTtsEngine getNuanceEngine() {
        return nuanceEngine;
    }

    public GoogleTtsEngine getGoogleEngine() {
        return googleEngine;
    }

    TtsEngine getFallbackEngine(String lang) {
        if ((googleEngine != null) && ("guj".equals(lang) || (nuanceEngine == null)))
            return googleEngine;
        return nuanceEngine;
    }


    private void registerVoices(TtsEngine engine) {
        for (String v : engine.getAvailableVoices()) {
            String voice = v;
            int voiceNameLength = v.indexOf('\'');
            if (voiceNameLength > 0) {
                voices.put(v.substring(8), engine.getSpeaker(v));
                validVoices.add(v);
                voice = v.substring(0, voiceNameLength);
            }
            String generalLanguage = voice.substring(0, 3);
            String countryLanguage = voice.substring(0, 7);
            String country = voice.substring(4, 7);
            String voiceName = voice.substring(8);
            languages.add(voice);
            languages.add(generalLanguage);
            generalVoices.add(voice);
            voices.put(voiceName, engine.getSpeaker(voice));
            registerDefaultVoice(generalLanguage, voice, engine);
            if (!ANY_COUNTRY.equals(country)) {
                languages.add(countryLanguage);
                registerDefaultVoice(countryLanguage, voice, engine);
            }
            String engineVoice = engine.getVoice(generalLanguage, country, voiceName);
            if (engineVoice != null) {
                if (engineVoice.equals(voice)) {
                    validVoices.add(voice);
                } else {
                    registerDefaultVoice(voice, engineVoice, engine);
                }
            }
        }
    }

    private void registerDefaultVoice(String name, String voice, TtsEngine engine) {
        if (!defaultVoices.containsKey(name))
            defaultVoices.put(name, voice);
    }

    @SuppressLint("InlinedApi")
    private void checkPostNotificationsPermission() {
        String optionKey = getString(R.string.quick_control_widget_option_key);
        if (preferences.getBoolean(optionKey, false) &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(optionKey, false);
            editor.apply();
        }
    }

    private void scheduleUiNotification() {
        if (uiNotifier != null) {
            handler.removeCallbacks(uiNotifier);
            handler.postDelayed(uiNotifier, 100);
        }
    }

    private synchronized void lockTtsService() {
        TtsService service = TtsService.getInstance();
        if (service != null)
            service.lock();
    }

    private synchronized void unlockTtsService() {
        TtsService service = TtsService.getInstance();
        if (service != null) {
            service.refresh();
            service.unlock();
        }
    }

}
