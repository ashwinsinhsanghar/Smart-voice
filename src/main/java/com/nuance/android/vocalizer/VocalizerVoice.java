package com.nuance.android.vocalizer;

/**
 * Voice descriptor. Instances are created by native code (AllocObject, no constructor is run),
 * so the field names and types below are dictated by libNuanceVocalizer.so and must not change.
 */
public class VocalizerVoice {
    public String mVoiceName;
    public int mSampleRate;
    public String mLanguage;
    public String mVoiceOperatingPoint;
    public String mLanguageVersion;
    public String mLanguageTLW;
    public String mVoiceAge;
    public String mVoiceType;
    public String mVoiceVersion;
    public int mLangId;

    @Override
    public String toString() {
        return "VocalizerVoice{name=" + mVoiceName + ", lang=" + mLanguage + ", tlw=" + mLanguageTLW +
            ", rate=" + mSampleRate + ", op=" + mVoiceOperatingPoint + ", age=" + mVoiceAge +
            ", type=" + mVoiceType + ", ver=" + mVoiceVersion + ", langId=" + mLangId + "}";
    }
}
