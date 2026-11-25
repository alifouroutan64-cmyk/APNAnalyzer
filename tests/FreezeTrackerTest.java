package com.sara.apnanalyzer;
public class FreezeTrackerTest {
  static void eq(long a,long b){if(a!=b)throw new AssertionError(a+" != "+b);}
  public static void main(String[] args){
    FreezeTracker t=new FreezeTracker(6000);
    t.observe(0,true); t.observe(2000,false);t.observe(4000,false);t.observe(6000,false);eq(t.events,0);
    t.observe(8000,false);eq(t.events,1);t.observe(10000,false);eq(t.events,1);
    t.observe(12000,true);eq(t.longest,10000);
    t.observe(14000,false);t.observe(16000,true);eq(t.events,1);
    t.observe(18000,false);t.observe(20000,false);t.observe(24000,false);eq(t.events,2);
    System.out.println("FreezeTracker: PASS");
  }
}
