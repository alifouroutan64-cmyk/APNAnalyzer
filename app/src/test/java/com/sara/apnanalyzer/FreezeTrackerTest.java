package com.sara.apnanalyzer;

import org.junit.Test;
import static org.junit.Assert.*;

public class FreezeTrackerTest {
    @Test public void shortInterruptionIsNotFreeze() {
        FreezeTracker tracker = new FreezeTracker(6000L);
        tracker.onFailure(1000L);
        tracker.onSuccess(5999L);
        assertEquals(0, tracker.getFreezeCount());
        assertEquals(0L, tracker.getTotalFreezeMs());
    }

    @Test public void interruptionAtThresholdCountsAsFreeze() {
        FreezeTracker tracker = new FreezeTracker(6000L);
        tracker.onFailure(1000L);
        tracker.onFailure(7000L);
        assertEquals(1, tracker.getFreezeCount());
        tracker.onSuccess(9000L);
        assertEquals(1, tracker.getFreezeCount());
        assertEquals(8000L, tracker.getTotalFreezeMs());
        assertEquals(8000L, tracker.getLongestFreezeMs());
    }

    @Test public void separateOutagesAreCountedSeparately() {
        FreezeTracker tracker = new FreezeTracker(6000L);
        tracker.onFailure(0L);
        tracker.onFailure(6000L);
        tracker.onSuccess(7000L);
        tracker.onFailure(10000L);
        tracker.onFailure(16000L);
        tracker.onSuccess(17000L);
        assertEquals(2, tracker.getFreezeCount());
        assertEquals(8000L, tracker.getTotalFreezeMs());
    }

    @Test public void unfinishedOutageIsCountedOnFinish() {
        FreezeTracker tracker = new FreezeTracker(6000L);
        tracker.onFailure(100L);
        tracker.finish(7100L);
        assertEquals(1, tracker.getFreezeCount());
        assertEquals(7000L, tracker.getTotalFreezeMs());
    }
}
