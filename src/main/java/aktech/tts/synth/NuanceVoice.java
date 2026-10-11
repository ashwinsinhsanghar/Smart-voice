package aktech.tts.synth;

import java.util.Collections;

import aktech.tts.smartvoice.api.Speaker;
import aktech.tts.smartvoice.api.TtsEngine;
import aktech.tts.smartvoice.api.Utils;

/** Android Voice descriptor for one Nuance Vocalizer voice. */
public class NuanceVoice extends Speaker {

    NuanceVoice(String name, String gender, TtsEngine engine) {
        super(name,
              gender,
              "Adult",
              engine,
              Utils.obtainLocale(name.substring(0, 3), name.substring(4, 7)),
              QUALITY_VERY_HIGH,
              LATENCY_LOW,
              false,
              Collections.emptySet());
    }

}
