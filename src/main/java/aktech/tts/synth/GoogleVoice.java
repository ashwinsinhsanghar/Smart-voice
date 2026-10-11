package aktech.tts.synth;

import java.util.Collections;

import aktech.tts.smartvoice.api.Speaker;
import aktech.tts.smartvoice.api.TtsEngine;
import aktech.tts.smartvoice.api.Utils;

/** Android Voice descriptor for one Google voice of the voice pack. */
public class GoogleVoice extends Speaker {

    GoogleVoice(String name, String gender, TtsEngine engine) {
        super(name,
              gender,
              "Adult",
              engine,
              Utils.obtainLocale(name.substring(0, 3), name.substring(4, 7)),
              QUALITY_VERY_HIGH,
              LATENCY_NORMAL,
              false,
              Collections.emptySet());
    }

}
