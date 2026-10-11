package com.nuance.android.vocalizer;

/** Speech mark record filled by native code (field names/types dictated by the library). */
public class VocalizerSpeechMark {
    public int mMarkType;
    public int mSrcPos;
    public int mSrcTextLen;
    public int mDestPos;
    public int mDestLen;
    public int mPhonemeId;
    public int mMarkId;
    public int mJawOpen;
    public int mTeethUpVisible;
    public int mTeethLoVisible;
    public int mMouthHeight;
    public int mMouthWidth;
    public int mMouthUpturn;
    public int mTonguePos;
    public int mLipTension;
    public String mLHPhoneme;
}
