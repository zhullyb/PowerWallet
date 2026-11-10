package cn.haiyu.powerwallet;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper-free mode switching on ROMs that natively support launch_QAW_wallet (verified:
 * Xiaomi 15, HyperOS OS4.0.0.22). Apps may not write this private System setting, so every
 * change goes through SelfAdb (shell identity over the phone's own wireless debugging).
 * HyperOS clears launch_QAW_wallet at boot; ReapplyJob writes it back.
 */
public final class NativeMode {
    static final String GOOGLE="launch_QAW_wallet", MI="mi_pay",
        GOOGLE_NFC="com.google.android.gms/com.google.android.gms.tapandpay.hce.service.TpHceService",
        MI_NFC="com.android.nfc/com.android.nfc.cardemulation.ESEWalletDummyService";
    private static final String GET="d=$(settings get system double_click_power_key); n=$(settings get secure nfc_payment_default_component); ";
    private static final String REPORT="echo \"AFTER $(settings get system double_click_power_key) $(settings get secure nfc_payment_default_component)\"";
    // Stop a legacy key helper without restoring: it would otherwise fight the native setting.
    private static final String KILL_HELPER="for p in $(ps -A -o PID,ARGS | grep '[a]pp_process /system/bin cn.haiyu' | awk '{print $1}'); do kill $p; done; ";
    private NativeMode() {}

    private static SharedPreferences prefs(Context c) {return c.getSharedPreferences("native",Context.MODE_PRIVATE);}
    public static String mode(Context c) {return prefs(c).getString("mode",null);}

    public static String apply(Context c, boolean google) throws Exception {
        String target=google?GOOGLE:MI, nfc=google?GOOGLE_NFC:MI_NFC;
        String out=SelfAdb.run(c,KILL_HELPER+GET+"echo \"BEFORE $d $n\"; "
            +"settings put system double_click_power_key "+target+"; "
            +"case \"$n\" in "+GOOGLE_NFC+"|"+MI_NFC+") settings put secure nfc_payment_default_component "+nfc+";; esac; "+REPORT);
        Matcher before=Pattern.compile("BEFORE (\\S+) (\\S+)").matcher(out);
        if(before.find() && !prefs(c).contains("backupDouble")) {
            String d=before.group(1);
            // null / our values mean an earlier run already replaced the user's choice; mi_pay is the factory default.
            prefs(c).edit().putString("backupDouble",d.equals("null")||d.equals(GOOGLE)||d.equals("google_pay")?MI:d)
                .putString("backupNfc",before.group(2)).apply();
        }
        String[] after=parseAfter(out);
        if(after==null || !target.equals(after[0])) throw new IllegalStateException("设置未生效："+out.trim());
        record(c,after[0]);
        return status(c);
    }

    public static String refresh(Context c) throws Exception {
        String[] after=parseAfter(SelfAdb.run(c,REPORT));
        if(after==null) throw new IllegalStateException("读取失败");
        record(c,after[0]);
        return status(c);
    }

    public static String restore(Context c) throws Exception {
        SharedPreferences p=prefs(c);
        if(!p.contains("backupDouble")) return "无需恢复：本应用尚未修改过设置\n"+status(c);
        String d=p.getString("backupDouble",MI), n=p.getString("backupNfc","null");
        String out=SelfAdb.run(c,KILL_HELPER+GET
            +"case \"$d\" in "+GOOGLE+"|"+MI+"|google_pay|null) settings put system double_click_power_key "+d+";; esac; "
            +("null".equals(n)?"":"case \"$n\" in "+GOOGLE_NFC+"|"+MI_NFC+") settings put secure nfc_payment_default_component "+n+";; esac; ")
            +REPORT);
        String[] after=parseAfter(out);
        p.edit().remove("backupDouble").remove("backupNfc").remove("mode").apply();
        if(after!=null) record(c,after[0]);
        return "已恢复\n"+status(c);
    }

    /**
     * After boot HyperOS may have replaced launch_QAW_wallet with null ("function not support")
     * or with its default mi_pay; both are indistinguishable from a user choice at this point,
     * so restore Google mode over them. Other functions the user picked are left alone; to keep
     * Mi Wallet across reboots, choose it in this app (mode is then not Google).
     */
    public static void reapplyAfterBoot(Context c) throws Exception {
        if(!GOOGLE.equals(mode(c))) return; // mi_pay survives reboots by itself.
        String out=SelfAdb.run(c,GET+"echo \"BEFORE $d $n\"; if [ \"$d\" = null ] || [ \"$d\" = "+MI+" ]; then settings put system double_click_power_key "+GOOGLE+"; fi; "+REPORT);
        String[] after=parseAfter(out);
        if(after!=null) record(c,after[0]);
        android.util.Log.i("PowerWallet","boot reapply: "+out.trim().replace('\n',' '));
    }

    private static String[] parseAfter(String out) {
        Matcher m=Pattern.compile("AFTER (\\S+) (\\S+)").matcher(out);
        return m.find()?new String[]{m.group(1),m.group(2)}:null;
    }
    private static void record(Context c, String dbl) {
        prefs(c).edit().putString("mode",dbl).putLong("checked",System.currentTimeMillis()).apply();
    }

    /** Fast status without connecting: last known double-press value plus live NFC default. */
    public static String status(Context c) {
        String d=mode(c);
        String dbl=GOOGLE.equals(d)?"Google 钱包":MI.equals(d)?"小米钱包":d==null?"未知（点「刷新状态」）":"其他功能（"+d+"）";
        String n=readNfc(c);
        String pay=GOOGLE_NFC.equals(n)?"Google 钱包":MI_NFC.equals(n)?"小米钱包":n==null?"未知":"其他应用";
        long t=prefs(c).getLong("checked",0);
        String when=t==0?"":"\n上次确认："+new SimpleDateFormat("MM-dd HH:mm",Locale.US).format(new Date(t));
        String setup=SelfAdb.paired(c)?"":"\n尚未配对无线调试";
        return "双击电源键：" +dbl+"\n默认付款："+pay+when+setup;
    }
    /** Hidden Secure settings are unreadable for apps; use the public CardEmulation API. */
    static String readNfc(Context c) {
        try {
            NfcAdapter a=NfcAdapter.getDefaultAdapter(c);
            if(a==null) return null;
            CardEmulation ce=CardEmulation.getInstance(a);
            for(String comp:new String[]{GOOGLE_NFC,MI_NFC})
                if(ce.isDefaultServiceForCategory(ComponentName.unflattenFromString(comp),CardEmulation.CATEGORY_PAYMENT)) return comp;
            return "other";
        } catch(Exception e) {return null;}
    }
}
