package com.nuance.android.vocalizer.internal;

/** Leak statistics filled by native code. */
public class VocalizerStatusInfo {
    public int mHeapBlocks;
    public int mSystemFileHandles;
    public int mAssetFileHandles;

    @Override
    public String toString() {
        return "heapBlocks=" + mHeapBlocks + " systemFileHandles=" + mSystemFileHandles + " assetFileHandles=" + mAssetFileHandles;
    }
}
