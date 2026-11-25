package com.sara.apnanalyzer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String PREF = "profiles_v1";
    private static final String HISTORY_KEY = "test_history_v1";
    private static final long TEST_DURATION_MS = 1800000L;
    private static final long PROBE_INTERVAL_MS = 2000L;
    private static final long FREEZE_THRESHOLD_MS = 6000L;
    private static final String PROBE_URL = "https://www.google.com/generate_204";

    private final ArrayList<Profile> profiles = new ArrayList<>();
    private final ArrayList<String> history = new ArrayList<>();
    private LinearLayout root, profileList;
    private TextView status, results, historyView, timerView, progressLabel, freezeLabel;
    private Spinner simSpinner, profileSpinner;
    private Button testButton;
    private ProgressBar progressBar;
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean testing = false;
    private volatile boolean stopRequested = false;
    private long testStartedAt;
    private int completed, successes, failures;
    private long totalLatency, minLatency = Long.MAX_VALUE, maxLatency;
    private final ArrayList<Long> latencySamples = new ArrayList<>();
    private FreezeTracker freezeTracker;
    private Handler handler = new Handler(Looper.getMainLooper());

    static class Profile {
        String name, apn, mcc, mnc, type, protocol, roamingProtocol, auth, username, password;
        String proxy, port, server, mmsc, mmsProxy, mmsPort, bearer, mvnoType, mvnoValue;
        int mtu;
        boolean hasTestResult;
        double lastScore, lastSuccessRate;
        long lastAverageLatency, lastLongestFreeze, lastTestAt;
        Profile(String n,String a,String c,String m,String t,String p,String au,String u,String pw,int mt) {
            name=n; apn=a; mcc=c; mnc=m; type=t; protocol=p; auth=au; username=u; password=pw; mtu=mt;
            roamingProtocol=p; proxy=""; port=""; server=""; mmsc=""; mmsProxy=""; mmsPort="";
            bearer="Unspecified"; mvnoType="None"; mvnoValue="";
        }
        JSONObject json() throws Exception {
            JSONObject j=new JSONObject();
            j.put("name",name); j.put("apn",apn); j.put("mcc",mcc); j.put("mnc",mnc); j.put("type",type);
            j.put("protocol",protocol); j.put("roamingProtocol",roamingProtocol); j.put("auth",auth);
            j.put("username",username); j.put("password",password); j.put("proxy",proxy); j.put("port",port);
            j.put("server",server); j.put("mmsc",mmsc); j.put("mmsProxy",mmsProxy); j.put("mmsPort",mmsPort);
            j.put("bearer",bearer); j.put("mtu",mtu); j.put("mvnoType",mvnoType); j.put("mvnoValue",mvnoValue);
            j.put("hasTestResult",hasTestResult);
            j.put("lastScore",lastScore);
            j.put("lastSuccessRate",lastSuccessRate);
            j.put("lastAverageLatency",lastAverageLatency);
            j.put("lastLongestFreeze",lastLongestFreeze);
            j.put("lastTestAt",lastTestAt);
            return j;
        }
        static Profile from(JSONObject j) {
            Profile p=new Profile(j.optString("name"),j.optString("apn"),j.optString("mcc","432"),j.optString("mnc","11"),
                    j.optString("type","default,supl"),j.optString("protocol","IPv4/IPv6"),j.optString("auth","None"),
                    j.optString("username"),j.optString("password"),j.optInt("mtu",0));
            p.roamingProtocol=j.optString("roamingProtocol",p.protocol); p.proxy=j.optString("proxy"); p.port=j.optString("port");
            p.server=j.optString("server"); p.mmsc=j.optString("mmsc"); p.mmsProxy=j.optString("mmsProxy");
            p.mmsPort=j.optString("mmsPort"); p.bearer=j.optString("bearer","Unspecified");
            p.mvnoType=j.optString("mvnoType","None"); p.mvnoValue=j.optString("mvnoValue","");
            p.hasTestResult=j.optBoolean("hasTestResult",false);
            p.lastScore=j.optDouble("lastScore",0);
            p.lastSuccessRate=j.optDouble("lastSuccessRate",0);
            p.lastAverageLatency=j.optLong("lastAverageLatency",0);
            p.lastLongestFreeze=j.optLong("lastLongestFreeze",0);
            p.lastTestAt=j.optLong("lastTestAt",0);
            return p;
        }
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b); getWindow().setStatusBarColor(0xff081321); getWindow().setNavigationBarColor(0xff081321);
        load(); loadHistory(); buildUi(); renderProfiles(); renderHistory();
    }

    private int dp(float x) { return (int)(x*getResources().getDisplayMetrics().density+.5f); }
    private TextView text(String s,int size,int color,boolean bold) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if(bold)t.setTypeface(null,Typeface.BOLD); t.setGravity(Gravity.RIGHT); t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setLineSpacing(dp(2),1f); return t;
    }
    private GradientDrawable shape(int color,int stroke,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke); return d;
    }
    private Button button(String label, boolean secondary) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(Color.WHITE);
        b.setPadding(dp(12),dp(8),dp(12),dp(8)); b.setBackground(shape(secondary?0xff263A52:0xff087F8C,secondary?0xff3D5874:0,14));
        return b;
    }
    private LinearLayout vertical() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card() {
        LinearLayout l=vertical(); l.setPadding(dp(16),dp(15),dp(16),dp(15)); l.setBackground(shape(0xff14243A,0xff263D58,20));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(9),0,dp(5)); l.setLayoutParams(p); return l;
    }
    private void buildUi() {
        root=vertical(); root.setPadding(dp(17),dp(18),dp(17),dp(26)); root.setBackgroundColor(0xff081321); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.addView(root); setContentView(scroll);

        LinearLayout hero=card(); hero.setBackground(shape(0xff12334A,0xff1A6070,24));
        TextView brand=text("APN ANALYZER",12,0xff67E8D2,true); brand.setGravity(Gravity.RIGHT); hero.addView(brand);
        TextView title=text("مرکز هوشمند پایش اینترنت همراه",22,Color.WHITE,true); title.setPadding(0,dp(5),0,dp(5)); hero.addView(title);
        hero.addView(text("نسخه 2.0  •  پایش پیوسته، تحلیل فریز و مدیریت پروفایل",12,0xffB9D8E8,false));
        root.addView(hero);

        LinearLayout sim=card(); sim.addView(text("اپراتور سیم‌کارت",15,Color.WHITE,true));
        simSpinner=new Spinner(this); String[] sims={"همراه اول","ایرانسل","رایتل","سایر / نامشخص"};
        ArrayAdapter<String> sa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,sims); sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); simSpinner.setAdapter(sa); sim.addView(simSpinner);
        sim.addView(text("این انتخاب فقط برچسب گزارش است؛ سیم‌کارت دیتای فعال را در تنظیمات گوشی کنترل کن.",11,0xffA9BED3,false)); root.addView(sim);

        LinearLayout actions=card(); actions.addView(text("مدیریت پروفایل",16,Color.WHITE,true));
        Button add=button("＋ افزودن پروفایل APN",false); add.setOnClickListener(v->profileDialog(null,-1)); actions.addView(add);
        Button settings=button("باز کردن تنظیمات APN گوشی",true);
        settings.setOnClickListener(v->openApnSettings());
        actions.addView(settings);
        Button best=button("★ پیشنهاد بهترین APN آزمایش‌شده",false);
        best.setOnClickListener(v->showBestApn());
        actions.addView(best);
        root.addView(actions);

        root.addView(text("پروفایل‌های ذخیره‌شده",18,Color.WHITE,true)); profileList=vertical(); root.addView(profileList);

        LinearLayout monitor=card(); monitor.setBackground(shape(0xff102B3D,0xff1B6171,22));
        monitor.addView(text("پایش ۳۰ دقیقه‌ای شبکه",18,Color.WHITE,true));
        monitor.addView(text("مدت: ۱۸۰۰ ثانیه     فاصله درخواست: ۲ ثانیه     آستانه فریز: ۶ ثانیه",12,0xffA8D8E1,false));
        profileSpinner=new Spinner(this); monitor.addView(text("پروفایل اعمال‌شده فعلی",12,0xffD5EAF0,true)); monitor.addView(profileSpinner);
        timerView=text("۳۰:۰۰",30,0xff67E8D2,true); timerView.setGravity(Gravity.CENTER); timerView.setPadding(0,dp(10),0,dp(8)); monitor.addView(timerView);
        progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progressBar.setMax(1800); progressBar.setProgress(0); monitor.addView(progressBar);
        progressLabel=text("۰٪ تکمیل شده",11,0xffA9BED3,false); monitor.addView(progressLabel);
        testButton=button("▶ شروع پایش ۳۰ دقیقه‌ای",false); testButton.setOnClickListener(v->{if(testing)stopTest();else runTest();}); monitor.addView(testButton);
        status=text("آماده شروع — وای‌فای و VPN را برای آزمون دیتای موبایل خاموش کن.",13,0xffA7F3D0,true); status.setPadding(0,dp(8),0,dp(4)); monitor.addView(status);
        freezeLabel=text("فریزهای شناسایی‌شده: ۰",14,0xffFBBF24,true); monitor.addView(freezeLabel);
        results=text("پس از شروع پایش، آمار لحظه‌ای و گزارش نهایی اینجا نمایش داده می‌شود.",13,Color.WHITE,false); results.setPadding(0,dp(8),0,0); monitor.addView(results); root.addView(monitor);

        LinearLayout hist=card(); hist.addView(text("تاریخچه آزمون‌ها",16,Color.WHITE,true)); historyView=text("هنوز آزمایشی ثبت نشده است.",12,Color.WHITE,false); hist.addView(historyView);
        Button clear=button("پاک کردن تاریخچه",true); clear.setOnClickListener(v->{history.clear();saveHistory();renderHistory();}); hist.addView(clear); root.addView(hist);

        LinearLayout note=card(); note.addView(text("راهنمای دقت نتایج",15,Color.WHITE,true));
        note.addView(text("• هر ۲ ثانیه یک درخواست HTTPS ارسال می‌شود.\n• اگر وقفه پیوسته در پاسخ‌گویی به ۶ ثانیه برسد، یک رخداد فریز ثبت می‌شود.\n• برنامه APN را مستقیماً تغییر نمی‌دهد؛ پروفایل را باید در تنظیمات اندروید اعمال کنی.\n• MTU و Bearer صرفاً در پروفایل نگهداری می‌شوند و تغییر رادیویی/مودمی ایجاد نمی‌کنند.\n• این سنجش، کیفیت اتصال اینترنت را از دید گوشی می‌سنجد و معادل Ping خام یا تست سرعت دانلود نیست.",12,0xffA9BED3,false)); root.addView(note);
    }

    private void renderProfiles() {
        profileList.removeAllViews();
        if(profileSpinner!=null) {
            ArrayList<String> labels=new ArrayList<>(); for(Profile x:profiles)labels.add(x.name+" — "+x.apn);
            if(labels.isEmpty())labels.add("ابتدا یک پروفایل بساز");
            ArrayAdapter<String> pa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels); pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            profileSpinner.setAdapter(pa); profileSpinner.setEnabled(!profiles.isEmpty());
        }
        if(profiles.isEmpty()){profileList.addView(text("هنوز پروفایلی ثبت نشده است.",13,0xff9CAFC5,false));return;}
        for(int i=0;i<profiles.size();i++) {
            final int ix=i; Profile p=profiles.get(i); LinearLayout c=card();
            c.addView(text(p.name+"  •  "+p.apn,16,Color.WHITE,true));
            c.addView(text("MCC: "+p.mcc+"  |  MNC: "+p.mnc+"  |  "+p.protocol+"  |  Bearer: "+p.bearer,11,0xffA9BDD2,false));
            if(p.hasTestResult) {
                c.addView(text(String.format(Locale.US,
                    "امتیاز: %.1f از ۱۰۰ | موفقیت: %.1f%% | میانگین: %d ms | طولانی‌ترین فریز: %d ثانیه",
                    p.lastScore,p.lastSuccessRate,p.lastAverageLatency,p.lastLongestFreeze/1000),
                    12,0xff67E8D2,true));
            } else {
                c.addView(text("هنوز آزمایش نشده است.",11,0xffA9BDD2,false));
            }
            LinearLayout row=new LinearLayout(this); Button edit=button("ویرایش",true); edit.setOnClickListener(v->profileDialog(p,ix));
            Button del=button("حذف",true); del.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("این پروفایل حذف شود؟").setNegativeButton("انصراف",null).setPositiveButton("حذف",(d,w)->{profiles.remove(ix);save();renderProfiles();}).show());
            row.addView(edit,new LinearLayout.LayoutParams(0,-2,1)); row.addView(del,new LinearLayout.LayoutParams(0,-2,1)); c.addView(row); profileList.addView(c);
        }
    }

    // Dialog uses dark text on its light Material surface: this fixes the invisible white input text.
    private void openApnSettings() {
        try {
            startActivity(new Intent("android.settings.APN_SETTINGS"));
        } catch(Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
            } catch(Exception ignored) {
                new AlertDialog.Builder(this)
                    .setMessage("از تنظیمات شبکه و سیم‌کارت وارد Access Point Names شو.")
                    .setPositiveButton("باشه",null).show();
            }
        }
    }

    private Profile findBestProfile() {
        Profile best=null;
        for(Profile p:profiles) {
            if(p.hasTestResult && (best==null || p.lastScore>best.lastScore)) best=p;
        }
        return best;
    }

    private void showBestApn() {
        final Profile best=findBestProfile();
        if(best==null) {
            new AlertDialog.Builder(this)
                .setTitle("هنوز نتیجه‌ای ثبت نشده")
                .setMessage("هر APN را جداگانه در تنظیمات گوشی فعال کن، سپس همان پروفایل را در برنامه انتخاب و آزمایش کن.")
                .setPositiveButton("تنظیمات APN",(d,w)->openApnSettings())
                .setNegativeButton("بستن",null).show();
            return;
        }

        String detail=String.format(Locale.US,
            "بهترین پروفایل آزمایش‌شده: %s\nAPN: %s\nامتیاز: %.1f از ۱۰۰\nنرخ موفقیت: %.1f%%\nمیانگین پاسخ: %d ms\nطولانی‌ترین فریز: %d ثانیه\n\nبرای فعال‌سازی، اطلاعات را در تنظیمات گوشی وارد یا انتخاب کن.",
            best.name,best.apn,best.lastScore,best.lastSuccessRate,
            best.lastAverageLatency,best.lastLongestFreeze/1000);

        new AlertDialog.Builder(this)
            .setTitle("نتیجه مقایسه APNها")
            .setMessage(detail)
            .setNeutralButton("کپی اطلاعات",(d,w)->{
                String copy="Name: "+best.name+
                    "\nAPN: "+best.apn+
                    "\nMCC: "+best.mcc+
                    "\nMNC: "+best.mnc+
                    "\nAPN Type: "+best.type+
                    "\nProtocol: "+best.protocol+
                    "\nRoaming protocol: "+best.roamingProtocol+
                    "\nAuthentication: "+best.auth+
                    "\nBearer: "+best.bearer;
                android.content.ClipboardManager cm=
                    (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Best APN",copy));
                Toast.makeText(this,"اطلاعات کپی شد.",Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton("فعال‌سازی در تنظیمات",(d,w)->openApnSettings())
            .setNegativeButton("بستن",null).show();
    }
    private EditText field(String hint,String value,boolean numeric) {
        EditText e=new EditText(this); e.setSingleLine(true); e.setText(value); e.setHint(hint); e.setTextSize(14);
        e.setTextColor(0xff172033); e.setHintTextColor(0xff78879A); e.setPadding(dp(12),dp(8),dp(12),dp(8));
        e.setBackground(shape(0xffF4F7FB,0xffCBD5E1,10)); e.setInputType(numeric?android.text.InputType.TYPE_CLASS_NUMBER:android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(4),0,dp(4)); e.setLayoutParams(p); return e;
    }
    private Spinner addDropdown(LinearLayout form,String label,String current,String[] options) {
        TextView title=text(label,13,0xff334155,true);
        title.setPadding(dp(2),dp(8),dp(2),dp(2));
        form.addView(title);
        Spinner spinner=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int selected=0;
        for(int i=0;i<options.length;i++) {
            if(options[i].equalsIgnoreCase(current==null?"":current.trim())) {
                selected=i;
                break;
            }
        }
        spinner.setSelection(selected);
        spinner.setPadding(dp(8),dp(4),dp(8),dp(4));
        form.addView(spinner,new LinearLayout.LayoutParams(-1,-2));
        return spinner;
    }

    private String selected(Spinner spinner) {
        Object item=spinner.getSelectedItem();
        return item==null?"":item.toString();
    }
    private class MultiSelectField {
        final String[] options;
        final boolean[] checked;
        final TextView summary;

        MultiSelectField(String[] options, boolean[] checked, TextView summary) {
            this.options = options;
            this.checked = checked;
            this.summary = summary;
        }

        String getValue() {
            ArrayList<String> values = new ArrayList<>();
            for (int i = 0; i < options.length; i++) {
                if (checked[i]) values.add(options[i]);
            }
            return android.text.TextUtils.join(",", values);
        }
    }

    private boolean hasChecked(boolean[] values) {
        for (boolean value : values) {
            if (value) return true;
        }
        return false;
    }

    private MultiSelectField addMultiSelect(
            LinearLayout form, String label, String current, String[] options) {

        TextView title = text(label, 13, 0xff334155, true);
        title.setPadding(dp(2), dp(8), dp(2), dp(2));
        form.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(shape(0xffF4F7FB, 0xffCBD5E1, 10));

        TextView summary = new TextView(this);
        summary.setTextColor(0xff172033);
        summary.setTextSize(14);
        summary.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        summary.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        row.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));

        TextView arrow = new TextView(this);
        arrow.setText("⌄");
        arrow.setTextSize(20);
        arrow.setTextColor(0xff334155);
        row.addView(arrow);

        boolean[] checked = new boolean[options.length];
        Set<String> initial = new HashSet<>();

        if (current != null) {
            for (String item : current.split(",")) {
                initial.add(item.trim().toLowerCase(Locale.ROOT));
            }
        }

        for (int i = 0; i < options.length; i++) {
            checked[i] = initial.contains(
                options[i].toLowerCase(Locale.ROOT));
        }

        if (!hasChecked(checked)) checked[0] = true;

        Runnable refresh = () -> {
            ArrayList<String> selected = new ArrayList<>();
            for (int i = 0; i < options.length; i++) {
                if (checked[i]) selected.add(options[i]);
            }
            summary.setText(android.text.TextUtils.join("، ", selected));
        };

        refresh.run();

        row.setOnClickListener(v -> {
            boolean[] draft = checked.clone();

            AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle(label)
                .setMultiChoiceItems(options, draft,
                    (dialog, which, isChecked) -> {

                        if (which == 0 && isChecked) {
                            for (int i = 1; i < draft.length; i++) {
                                draft[i] = false;
                            }
                        } else if (which > 0 && isChecked) {
                            draft[0] = false;
                        }

                        ListView list = ((AlertDialog) dialog).getListView();
                        for (int i = 0; i < draft.length; i++) {
                            list.setItemChecked(i, draft[i]);
                        }
                    })
                .setNegativeButton("انصراف", null)
                .setPositiveButton("تأیید", (dialog, which) -> {
                    System.arraycopy(draft, 0, checked, 0, checked.length);
                    if (!hasChecked(checked)) checked[0] = true;
                    refresh.run();
                })
                .create();

            picker.show();
        });

        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        form.addView(row, lp);

        return new MultiSelectField(options, checked, summary);
    }
    private void profileDialog(Profile old,int index) {
        LinearLayout form=vertical(); form.setPadding(dp(12),dp(8),dp(12),dp(8));
        EditText name=field("نام پروفایل",old==null?"MCI-GPRS":old.name,false);
        EditText apn=field("APN",old==null?"mcinet":old.apn,false);
        EditText mcc=field("MCC",old==null?"432":old.mcc,true),mnc=field("MNC",old==null?"11":old.mnc,true);
        MultiSelectField type=addMultiSelect(form,"APN Type",old==null?"default,supl":old.type,new String[]{"default","mms","supl","dun","hipri","fota","ims","cbs","xcap","emergency"});
        Spinner auth=addDropdown(form,"Authentication type",old==null?"None":old.auth,new String[]{"None","PAP","CHAP","PAP or CHAP"});
        Spinner proto=addDropdown(form,"APN protocol",old==null?"IPv4/IPv6":old.protocol,new String[]{"IPv4","IPv6","IPv4/IPv6"});
        Spinner roam=addDropdown(form,"APN roaming protocol",old==null?"IPv4/IPv6":old.roamingProtocol,new String[]{"IPv4","IPv6","IPv4/IPv6"});
        EditText user=field("Username",old==null?"":old.username,false),pass=field("Password",old==null?"":old.password,false);
        EditText proxy=field("Proxy",old==null?"":old.proxy,false),port=field("Port",old==null?"":old.port,true);
        EditText server=field("Server",old==null?"":old.server,false),mmsc=field("MMSC",old==null?"":old.mmsc,false);
        EditText mmsProxy=field("MMS Proxy",old==null?"":old.mmsProxy,false),mmsPort=field("MMS Port",old==null?"":old.mmsPort,true);
        MultiSelectField bearer=addMultiSelect(form,"Bearer",old==null?"Unspecified":old.bearer,new String[]{"Unspecified","LTE","HSPAP","HSPA","HSUPA","HSDPA","UMTS","EDGE","GPRS","eHRPD","EVDO_B","EVDO_A","EVDO_0","1xRTT","IS95B","IS95A","NR"});
        EditText mtu=field("MTU (خالی یا صفر = خودکار)",old==null?"0":String.valueOf(old.mtu),true);
        Spinner mvnoType=addDropdown(form,"MVNO type",old==null?"None":old.mvnoType,new String[]{"None","SPN","IMSI","GID","ICCID"});
        EditText mvnoValue=field("MVNO value",old==null?"":old.mvnoValue,false);
        TextView hint=text("گزینه‌های کشویی مطابق تنظیمات APN اندروید هستند. قبل از ذخیره، مقادیر را با اپراتور تطبیق بده.",11,0xff475569,false);
        hint.setPadding(0,0,0,dp(6));
        form.addView(hint,0);
        for(EditText e:new EditText[]{name,apn,mcc,mnc,user,pass,proxy,port,server,mmsc,mmsProxy,mmsPort,mtu,mvnoValue})form.addView(e);
        ScrollView sv=new ScrollView(this); sv.setFillViewport(false); sv.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(old==null?"ساخت پروفایل APN":"ویرایش پروفایل APN").setView(sv)
                .setNegativeButton("انصراف",null).setPositiveButton("ذخیره پروفایل",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                int mtuVal=mtu.getText().toString().trim().isEmpty()?0:Integer.parseInt(mtu.getText().toString().trim());
                Profile p=new Profile(val(name),val(apn),val(mcc),val(mnc),type.getValue(),selected(proto),selected(auth),val(user),val(pass),mtuVal);
                p.roamingProtocol=selected(roam); p.proxy=val(proxy); p.port=val(port); p.server=val(server); p.mmsc=val(mmsc);
                p.mmsProxy=val(mmsProxy); p.mmsPort=val(mmsPort); p.bearer=bearer.getValue(); p.mvnoType=selected(mvnoType); p.mvnoValue=val(mvnoValue);
                if(p.name.isEmpty()||p.apn.isEmpty())throw new IllegalArgumentException("نام و APN الزامی است");
                if(!p.mcc.matches("\\d{3}")||!p.mnc.matches("\\d{2,3}"))throw new IllegalArgumentException("MCC باید ۳ رقم و MNC باید ۲ یا ۳ رقم باشد.");
                if(index>=0)profiles.set(index,p);else profiles.add(p); save(); renderProfiles(); dialog.dismiss();
            } catch(Exception ex) { new AlertDialog.Builder(this).setMessage("اطلاعات معتبر نیست. نام و APN را وارد کن؛ MCC سه رقم، MNC دو یا سه رقم و MTU عددی باشد.").setPositiveButton("متوجه شدم",null).show(); }
        })); dialog.show();
    }
    private String val(EditText e){return e.getText().toString().trim();}

    private void runTest() {
        if(testing)return;
        if(profiles.isEmpty()) {
            new AlertDialog.Builder(this)
                .setMessage("ابتدا یک پروفایل APN بساز.")
                .setPositiveButton("باشه",null).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("تأیید پروفایل فعال")
            .setMessage("آیا همین پروفایل را قبلاً در تنظیمات APN گوشی فعال کرده‌ای؟ انتخاب پروفایل در برنامه به‌تنهایی تنظیمات گوشی را تغییر نمی‌دهد.")
            .setNegativeButton("خیر",null)
            .setPositiveButton("بله، شروع آزمایش",(d,w)->startTestConfirmed())
            .show();
    }

    private void startTestConfirmed() {
        if(testing)return;
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE); Network n=cm.getActiveNetwork();
        NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
        if(caps==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){status.setText("اتصال اینترنت فعال پیدا نشد.");return;}
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){new AlertDialog.Builder(this).setMessage("وای‌فای متصل است. برای سنجش دیتای سیم‌کارت، وای‌فای را خاموش کن.").setPositiveButton("باشه",null).show();return;}
        if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)){status.setText("اتصال موبایل تشخیص داده نشد. VPN را نیز موقتاً خاموش کن.");return;}
        testing=true; stopRequested=false; completed=successes=failures=0; totalLatency=maxLatency=0; minLatency=Long.MAX_VALUE; latencySamples.clear();
        testStartedAt=SystemClock.elapsedRealtime();
        freezeTracker=new FreezeTracker(FREEZE_THRESHOLD_MS,System.currentTimeMillis(),testStartedAt);
        testButton.setText("■ توقف پایش"); progressBar.setProgress(0); progressLabel.setText("۰٪ تکمیل شده"); status.setText("پایش آغاز شد؛ برنامه را در پیش‌زمینه نگه دار."); results.setText("در حال جمع‌آوری داده‌های شبکه…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final String sim=String.valueOf(simSpinner.getSelectedItem());
        final String profile=(profileSpinner.getSelectedItemPosition()>=0&& !profiles.isEmpty())?profiles.get(profileSpinner.getSelectedItemPosition()).name:"نامشخص";
        worker.execute(()->monitorLoop(sim,profile));
    }
    private void stopTest(){stopRequested=true;status.setText("درخواست توقف ثبت شد؛ گزارش تا این لحظه آماده می‌شود…");testButton.setEnabled(false);}

    private void monitorLoop(String sim,String profile) {
        long nextProbe=SystemClock.elapsedRealtime(); long endAt=testStartedAt+TEST_DURATION_MS; long lastUi=0;
        while(!stopRequested && SystemClock.elapsedRealtime()<endAt) {
            long now=SystemClock.elapsedRealtime();
            if(now<nextProbe){try{Thread.sleep(Math.min(100,nextProbe-now));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}continue;}
            long started=SystemClock.elapsedRealtime(); boolean ok=false; long latency=-1; int http=-1;
            HttpURLConnection c=null;
            try {
                c=(HttpURLConnection)new URL(PROBE_URL).openConnection(); c.setConnectTimeout(1500); c.setReadTimeout(1500);
                c.setRequestMethod("GET"); c.setUseCaches(false); c.setInstanceFollowRedirects(false); c.setRequestProperty("Cache-Control","no-cache");
                long t=SystemClock.elapsedRealtime(); http=c.getResponseCode(); latency=SystemClock.elapsedRealtime()-t; ok=(http==204 || (http>=200&&http<400));
            } catch(Exception ignored) { ok=false; }
            finally {if(c!=null)c.disconnect();}
            long finished=SystemClock.elapsedRealtime(); completed++;
            if(ok){successes++;totalLatency+=latency;minLatency=Math.min(minLatency,latency);maxLatency=Math.max(maxLatency,latency);latencySamples.add(latency);freezeTracker.onSuccess(finished);}
            else {failures++;freezeTracker.onFailure(started,finished);}
            if(finished-lastUi>=500 || completed==1){lastUi=finished; final boolean fOk=ok; final long fLatency=latency; final int fHttp=http; final long elapsed=finished-testStartedAt; final int count=completed; final int good=successes; final int bad=failures; final int freezes=freezeTracker.getFreezeCount(); final long outage=freezeTracker.getCurrentOutageMs(finished); handler.post(()->updateLiveUi(elapsed,count,good,bad,freezes,outage,fOk,fLatency,fHttp));}
            nextProbe+=PROBE_INTERVAL_MS;
            if(nextProbe<finished)nextProbe=finished+PROBE_INTERVAL_MS;
        }
        long finished=SystemClock.elapsedRealtime(); freezeTracker.finish(finished);
        final long elapsed=finished-testStartedAt; final int count=completed,good=successes,bad=failures,freezes=freezeTracker.getFreezeCount();
        final long avg=successes==0?0:totalLatency/successes, longest=freezeTracker.getLongestFreezeMs(), totalFreeze=freezeTracker.getTotalFreezeMs();
        final long min=minLatency==Long.MAX_VALUE?0:minLatency, max=maxLatency;
        final String record=java.text.DateFormat.getDateTimeInstance().format(new Date())+" | "+sim+" | "+profile+" | موفق "+good+"/"+count+" | فریز "+freezes+" | میانگین "+avg+"ms";
        handler.post(()->finishUi(elapsed,count,good,bad,freezes,avg,min,max,longest,totalFreeze,record,profile));
    }
    private void updateLiveUi(long elapsed,int count,int good,int bad,int freezes,long outage,boolean lastOk,long latency,int http) {
        int sec=(int)Math.min(1800,elapsed/1000); int remain=Math.max(0,1800-sec); timerView.setText(String.format(Locale.US,"%02d:%02d",remain/60,remain%60));
        progressBar.setProgress(sec); progressLabel.setText((sec*100/1800)+"٪ تکمیل شده  •  "+count+" درخواست");
        freezeLabel.setText("فریزهای شناسایی‌شده: "+freezes+(outage>=FREEZE_THRESHOLD_MS?"  •  اختلال جاری: "+(outage/1000)+" ثانیه":""));
        status.setText(lastOk?"اتصال برقرار است  •  آخرین پاسخ "+latency+" ms":"پاسخ ناموفق در آخرین درخواست");
        results.setText("موفق: "+good+"   ناموفق: "+bad+"\nنرخ موفقیت: "+(count==0?0:(good*100/count))+"٪\nآخرین کد HTTP: "+(http<0?"—":http));
    }
    private void finishUi(long elapsed,int count,int good,int bad,int freezes,long avg,long min,long max,long longest,long totalFreeze,String record,String profileName) {
        testing=false; stopRequested=false; testButton.setEnabled(true); testButton.setText("▶ شروع پایش ۳۰ دقیقه‌ای"); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int sec=(int)Math.min(1800,elapsed/1000); progressBar.setProgress(sec); progressLabel.setText((sec*100/1800)+"٪ تکمیل شده");
        timerView.setText("پایان پایش"); freezeLabel.setText("فریزهای شناسایی‌شده: "+freezes);
        status.setText(elapsed>=TEST_DURATION_MS?"پایش ۳۰ دقیقه‌ای کامل شد.":"پایش متوقف شد و گزارش نهایی آماده است.");
        double successRate=count==0?0:(good*100.0/count);
        double stabilityScore=Math.max(0,100.0-(totalFreeze/1000.0*2.0));
        double latencyScore=good==0?0:Math.max(0,100.0-(avg/20.0));
        double score=count==0?0:(successRate*0.60+stabilityScore*0.25+latencyScore*0.15);

        Profile tested=null;
        for(Profile p:profiles) if(p.name.equals(profileName)){tested=p;break;}
        if(tested!=null && count>0) {
            tested.hasTestResult=true;
            tested.lastScore=score;
            tested.lastSuccessRate=successRate;
            tested.lastAverageLatency=avg;
            tested.lastLongestFreeze=longest;
            tested.lastTestAt=System.currentTimeMillis();
            save();
            renderProfiles();
        }

        StringBuilder report=new StringBuilder(
            "مدت پایش: "+(elapsed/1000)+" ثانیه\n"+
            "کل درخواست‌ها: "+count+"\n"+
            "موفق: "+good+" | ناموفق: "+bad+"\n"+
            "نرخ موفقیت: "+String.format(Locale.US,"%.1f",successRate)+"٪\n"+
            "تعداد فریزها: "+freezes+"\n"+
            "مجموع زمان فریز: "+(totalFreeze/1000)+" ثانیه\n"+
            "طولانی‌ترین فریز: "+(longest/1000)+" ثانیه\n"+
            "میانگین پاسخ موفق: "+(good==0?"—":avg+" ms")+"\n"+
            "کمترین / بیشترین پاسخ: "+(good==0?"—":min+" / "+max+" ms")+
            "\n\nامتیاز پروفایل: "+String.format(Locale.US,"%.1f",score)+" از ۱۰۰"+
            "\nوزن‌ها: موفقیت ۶۰٪، پایداری ۲۵٪، تأخیر ۱۵٪");

        List<FreezeTracker.FreezeEvent> events=freezeTracker.getEvents();
        report.append("\n\nجزئیات فریزهای شناسایی‌شده:");
        if(events.isEmpty()) {
            report.append("\nهیچ فریز حداقل ۶ ثانیه‌ای ثبت نشد.");
        } else {
            for(int i=0;i<events.size();i++) {
                FreezeTracker.FreezeEvent e=events.get(i);
                report.append("\n\nفریز ").append(i+1)
                    .append(": ").append(formatTime(e.startWallMs))
                    .append(" تا ").append(formatTime(e.endWallMs))
                    .append("\nمدت: ").append(e.durationMs/1000)
                    .append(" ثانیه | درخواست ناموفق: ").append(e.failedRequests);
            }
        }
        report.append("\n\nتوجه: این آزمایش HTTPS معادل تست سرعت دانلود یا Ping خام نیست. رتبه‌بندی فقط میان APNهایی معتبر است که جداگانه فعال و آزمایش شده‌اند.");
        results.setText(report.toString());

        if(count>0) {
            history.add(0,record+" | امتیاز "+String.format(Locale.US,"%.1f",score));
            while(history.size()>30)history.remove(history.size()-1);
            saveHistory();
            renderHistory();
        }
    }

    private String formatTime(long millis) {
        return new java.text.SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(millis));
    }
    private void loadHistory(){try{JSONArray a=new JSONArray(getSharedPreferences("apn",0).getString(HISTORY_KEY,"[]"));for(int i=0;i<a.length();i++)history.add(a.getString(i));}catch(Exception ignored){}}
    private void saveHistory(){try{JSONArray a=new JSONArray();for(String x:history)a.put(x);getSharedPreferences("apn",0).edit().putString(HISTORY_KEY,a.toString()).apply();}catch(Exception ignored){}}
    private void renderHistory(){if(historyView==null)return;if(history.isEmpty()){historyView.setText("هنوز آزمایشی ثبت نشده است.");return;}StringBuilder b=new StringBuilder();for(int i=0;i<history.size();i++)b.append(i+1).append(". ").append(history.get(i)).append("\n\n");historyView.setText(b.toString());}
    private void save(){try{JSONArray a=new JSONArray();for(Profile p:profiles)a.put(p.json());getSharedPreferences("apn",0).edit().putString(PREF,a.toString()).apply();}catch(Exception ignored){}}
    private void load(){try{JSONArray a=new JSONArray(getSharedPreferences("apn",0).getString(PREF,"[]"));for(int i=0;i<a.length();i++)profiles.add(Profile.from(a.getJSONObject(i)));}catch(Exception ignored){}}
    @Override protected void onDestroy(){stopRequested=true;super.onDestroy();worker.shutdownNow();}
}
