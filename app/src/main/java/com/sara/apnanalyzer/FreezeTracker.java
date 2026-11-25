package com.sara.apnanalyzer;

/** Tracks continuous periods without a successful network probe. */
public final class FreezeTracker {
    public static final long DEFAULT_THRESHOLD_MS = 6000L;
    private final long thresholdMs;
    private long outageStartMs = -1L;
    private boolean currentOutageCounted = false;
    private int freezeCount = 0;
    private long totalFreezeMs = 0L;
    private long longestFreezeMs = 0L;

    public FreezeTracker() { this(DEFAULT_THRESHOLD_MS); }
    public FreezeTracker(long thresholdMs) { this.thresholdMs = Math.max(1L, thresholdMs); }

    public void onFailure(long nowMs) {
        if (outageStartMs < 0L) outageStartMs = nowMs;
        long duration = Math.max(0L, nowMs - outageStartMs);
        if (!currentOutageCounted && duration >= thresholdMs) {
            freezeCount++;
            currentOutageCounted = true;
        }
    }

    public void onSuccess(long nowMs) {
        closeOutage(nowMs);
    }

    public void finish(long nowMs) { closeOutage(nowMs); }

    private void closeOutage(long nowMs) {
        if (outageStartMs < 0L) return;
        long duration = Math.max(0L, nowMs - outageStartMs);
        if (duration >= thresholdMs) {
            if (!currentOutageCounted) freezeCount++;
            totalFreezeMs += duration;
            longestFreezeMs = Math.max(longestFreezeMs, duration);
        }
        outageStartMs = -1L;
        currentOutageCounted = false;
    }

    public int getFreezeCount() { return freezeCount; }
    public long getTotalFreezeMs() { return totalFreezeMs; }
    public long getLongestFreezeMs() { return longestFreezeMs; }
    public long getCurrentOutageMs(long nowMs) {
        return outageStartMs < 0L ? 0L : Math.max(0L, nowMs - outageStartMs);
    }
    public boolean isCurrentlyFrozen(long nowMs) { return getCurrentOutageMs(nowMs) >= thresholdMs; }
}
