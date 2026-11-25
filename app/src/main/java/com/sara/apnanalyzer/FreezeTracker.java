package com.sara.apnanalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tracks continuous periods without a successful network probe. */
public final class FreezeTracker {
    public static final long DEFAULT_THRESHOLD_MS = 6000L;

    public static final class FreezeEvent {
        public final long startWallMs;
        public final long endWallMs;
        public final long durationMs;
        public final int failedRequests;

        FreezeEvent(long start, long end, int failures) {
            startWallMs = start;
            endWallMs = end;
            durationMs = Math.max(0L, end - start);
            failedRequests = failures;
        }
    }

    private final long thresholdMs;
    private final long wallStartMs;
    private final long elapsedStartMs;

    private long outageStartMs = -1L;
    private boolean currentOutageCounted = false;
    private int freezeCount = 0;
    private int currentFailedRequests = 0;
    private long totalFreezeMs = 0L;
    private long longestFreezeMs = 0L;

    private final ArrayList<FreezeEvent> events = new ArrayList<>();

    public FreezeTracker() {
        this(DEFAULT_THRESHOLD_MS);
    }

    public FreezeTracker(long thresholdMs) {
        this(thresholdMs, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime());
    }

    public FreezeTracker(long thresholdMs, long wallStartMs, long elapsedStartMs) {
        this.thresholdMs = Math.max(1L, thresholdMs);
        this.wallStartMs = wallStartMs;
        this.elapsedStartMs = elapsedStartMs;
    }

    public void onFailure(long nowMs) {
        onFailure(nowMs, nowMs);
    }

    public void onFailure(long requestStartMs, long observedAtMs) {
        if (outageStartMs < 0L) {
            outageStartMs = requestStartMs;
            currentFailedRequests = 0;
        }
        currentFailedRequests++;

        long duration = Math.max(0L, observedAtMs - outageStartMs);
        if (!currentOutageCounted && duration >= thresholdMs) {
            freezeCount++;
            currentOutageCounted = true;
        }
    }

    public void onSuccess(long nowMs) {
        closeOutage(nowMs);
    }

    public void finish(long nowMs) {
        closeOutage(nowMs);
    }

    private long toWallTime(long elapsedMs) {
        return wallStartMs + (elapsedMs - elapsedStartMs);
    }

    private void closeOutage(long nowMs) {
        if (outageStartMs < 0L) return;

        long duration = Math.max(0L, nowMs - outageStartMs);
        if (duration >= thresholdMs) {
            if (!currentOutageCounted) freezeCount++;

            totalFreezeMs += duration;
            longestFreezeMs = Math.max(longestFreezeMs, duration);

            events.add(new FreezeEvent(
                toWallTime(outageStartMs),
                toWallTime(nowMs),
                currentFailedRequests
            ));
        }

        outageStartMs = -1L;
        currentOutageCounted = false;
        currentFailedRequests = 0;
    }

    public int getFreezeCount() { return freezeCount; }
    public long getTotalFreezeMs() { return totalFreezeMs; }
    public long getLongestFreezeMs() { return longestFreezeMs; }

    public long getCurrentOutageMs(long nowMs) {
        return outageStartMs < 0L ? 0L : Math.max(0L, nowMs - outageStartMs);
    }

    public boolean isCurrentlyFrozen(long nowMs) {
        return getCurrentOutageMs(nowMs) >= thresholdMs;
    }

    public List<FreezeEvent> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}