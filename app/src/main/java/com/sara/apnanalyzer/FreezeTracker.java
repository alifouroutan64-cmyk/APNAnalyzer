package com.sara.apnanalyzer;
/** Counts separate intervals of observed failure using monotonic timestamps. */
final class FreezeTracker {
    private final long threshold;
    private long failedSince=-1;
    private boolean counted;
    int events;
    long longest;
    FreezeTracker(long threshold){this.threshold=threshold;}
    void observe(long time,boolean good){
        if(good){
            if(failedSince>=0)longest=Math.max(longest,time-failedSince);
            failedSince=-1;counted=false;return;
        }
        if(failedSince<0)failedSince=time;
        long span=time-failedSince;
        longest=Math.max(longest,span);
        if(span>=threshold&&!counted){events++;counted=true;}
    }
}
