package aktech.tts.smartvoice.ui;

import java.util.SortedSet;
import java.util.TreeSet;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.preference.ListPreference;
import android.view.View;
import android.widget.Button;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.PreferenceGroup;
import java.util.HashMap;
import java.util.Map;
import aktech.tts.synth.NuanceTtsEngine;

import aktech.tts.smartvoice.R;
import aktech.tts.smartvoice.SmartVoiceApp;

public class VoiceSettingsFragment extends MenuProviderPreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.voice_preferences);
        String voice = getArguments().getString(getString(R.string.voice_key));
        if (voice != null) {
            SmartVoiceApp app = (SmartVoiceApp)getActivity().getApplication();
            PreferenceGroup preferences = (PreferenceGroup)findPreference(getString(R.string.voice_prefs_key));
            int nPrefs = preferences.getPreferenceCount();
            final Map<String, Slider> sliders = new HashMap<String, Slider>();
            for (int i = 0; i < nPrefs; i++) {
                Slider preference = (Slider) preferences.getPreference(i);
                sliders.put(preference.getKey(), preference);
                String newKey = getString(R.string.voice_preference_key_format, app.getInternalVoiceName(voice), preference.getKey());
                preference.setKey(newKey);
                preference.setValue(preference.getPersistedInt(preference.getValue()));
            }

            Preference test = new Preference(preferences.getContext()) {
                @Override
                public void onBindViewHolder(PreferenceViewHolder holder) {
                    super.onBindViewHolder(holder);
                    // announced by screen readers as a button
                    ViewCompat.setAccessibilityDelegate(holder.itemView, new AccessibilityDelegateCompat() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            info.setClassName(Button.class.getName());
                            info.setClickable(true);
                        }
                    });
                }
            };
            test.setTitle(R.string.pref_test_voice_title);
            test.setPersistent(false);
            final String voiceSpec = voice;
            test.setOnPreferenceClickListener(p -> {
                runVoiceTest(app, voiceSpec, sliders);
                return true;
            });
            preferences.addPreference(test);

            SortedSet<String> models = new TreeSet<String>();
            for (String model : app.validVoices)
                if ((model.length() > voice.length()) && model.startsWith(voice) && (model.charAt(voice.length()) == '\''))
                    models.add(model);
            if (models.size() > 0) {
                ListPreference pref = new ListPreference(preferences.getContext());
                pref.setDialogTitle(R.string.pref_choose_voice_model);
                pref.setDefaultValue(app.defaultVoices.get(voice));
                String entryValues[] = models.toArray(new String[0]);
                String entries[] = new String[entryValues.length];
                for (int i = 0; i < entries.length; i++) {
                    int modelNameStart = entryValues[i].indexOf('\'') + 1;
                    String modelName = (modelNameStart > 0) ? entryValues[i].substring(modelNameStart).replace('/', '-') : "";
                    if (modelName.isEmpty())
                        modelName = String.valueOf(i + 1);
                    entries[i] = modelName;
                }
                pref.setEntries(entries);
                pref.setEntryValues(entryValues);
                pref.setKey(voice);
                pref.setPersistent(true);
                pref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                pref.setTitle(R.string.pref_voice_model_title);
                pref.setEnabled(entries.length > 1);
                preferences.addPreference(pref);
            }
        }
    }

    private int sliderValue(Map<String, Slider> sliders, int keyRes, int defRes) {
        Slider sl = sliders.get(getString(keyRes));
        return (sl != null) ? sl.getValue() : getResources().getInteger(defRes);
    }

    private void runVoiceTest(SmartVoiceApp app, String voiceSpec, Map<String, Slider> sliders) {
        if (voiceSpec.startsWith("guj")) {
            final aktech.tts.synth.GoogleTtsEngine googleEngine = app.getGoogleEngine();
            if (googleEngine == null)
                return;
            final int gVolume = sliderValue(sliders, R.string.volume_key, R.integer.volume_value);
            final int gRate = sliderValue(sliders, R.string.rate_key, R.integer.rate_value);
            final int gPitch = sliderValue(sliders, R.string.pitch_key, R.integer.pitch_value);
            final String gText = getString(R.string.test_voice_sample_guj);
            new Thread(() -> googleEngine.selfTest(voiceSpec, gText, gRate, gPitch, gVolume), "voice-test").start();
            return;
        }
        final NuanceTtsEngine engine = app.getNuanceEngine();
        if (engine == null)
            return;
        final int volume = sliderValue(sliders, R.string.volume_key, R.integer.volume_value);
        final int rate = sliderValue(sliders, R.string.rate_key, R.integer.rate_value);
        final int pitch = sliderValue(sliders, R.string.pitch_key, R.integer.pitch_value);
        final String text = getString(voiceSpec.startsWith("hin") ? R.string.test_voice_sample_hin : R.string.test_voice_sample_eng);
        new Thread(() -> engine.selfTest(voiceSpec, text, rate, pitch, volume), "voice-test").start();
    }

    @Override
    public void onPrepareMenu(Menu menu) {
        boolean showRefreshButton = ((TtsPreferenceActivity) getActivity()).hasHeaders();
        MenuItem refreshButton = menu.findItem(R.id.action_refresh);
        refreshButton.setEnabled(showRefreshButton);
        refreshButton.setVisible(showRefreshButton);
    }

}
