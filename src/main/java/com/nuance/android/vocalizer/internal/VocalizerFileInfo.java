package com.nuance.android.vocalizer.internal;

/** Returned from VocalizerEngine.openAssetFile(); read by native code. */
public class VocalizerFileInfo {
    public int descriptor;
    public long startOffset;
    public long length;

    public VocalizerFileInfo(int descriptor, long startOffset, long length) {
        this.descriptor = descriptor;
        this.startOffset = startOffset;
        this.length = length;
    }
}
