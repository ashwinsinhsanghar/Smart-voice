package com.nuance.android.vocalizer;

/** Engine version record filled by native code. */
public class VocalizerVersion {
    public int mMajor;
    public int mMinor;
    public int mBuild;
    public int mBuildYear;
    public int mBuildMonth;
    public int mBuildDay;
    public String mBuildInfo;

    @Override
    public String toString() {
        return mMajor + "." + mMinor + "." + mBuild + " (" + mBuildYear + "-" + mBuildMonth + "-" + mBuildDay + ") " + mBuildInfo;
    }
}
