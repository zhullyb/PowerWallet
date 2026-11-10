package cn.haiyu.powerwallet;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Runs as Android's ADB shell identity; never inside the ordinary app process. */
public final class Helper {
    private static final String KEY="double_click_power_key";
    private static final File STATE=new File("/data/local/tmp/powerwallet-state.properties");
    /** Validated device|incremental pairs. Others need POWERWALLET_ALLOW_UNVERIFIED=1. */
    private static final String[] VERIFIED={"ishtar|OS3.0.308.0.WMACNXM","dada|OS4.0.0.22.XOCCNXM"};
    private static final String MARKER=markerValue(), MI="mi_pay";
    /** Our two owned values: Google mode (MARKER, a no-op the helper fills) or native Mi Pay mode. */
    private static boolean ours(String v) {return MARKER.equals(v) || MI.equals(v);}
    /** Default NFC payment service that goes with each mode (Settings.Secure nfc_payment_default_component). */
    private static final String NFC_KEY="nfc_payment_default_component",
        GOOGLE_NFC="com.google.android.gms/com.google.android.gms.tapandpay.hce.service.TpHceService",
        MI_NFC="com.android.nfc/com.android.nfc.cardemulation.ESEWalletDummyService";
    private static boolean ourNfc(String v) {return GOOGLE_NFC.equals(v) || MI_NFC.equals(v);}
    private static String nfc() throws Exception {return command("/system/bin/settings","--user","0","get","secure",NFC_KEY);}
    private static void putNfc(String value) throws Exception {
        if(value==null) return;
        if("null".equals(value)) command("/system/bin/settings","--user","0","delete","secure",NFC_KEY);
        else command("/system/bin/settings","--user","0","put","secure",NFC_KEY,value);
    }
    private static boolean nfcServiceExists(String component) {
        try {return command("/system/bin/dumpsys","nfc").contains("ComponentInfo{"+component+"}");} catch(Exception e) {return false;}
    }
    /** Switches the default payment app along with the mode, unless the user picked a third app. */
    private static String applyNfc(String mode) throws Exception {
        String current=nfc(), target=MI.equals(mode)?MI_NFC:GOOGLE_NFC;
        if(target.equals(current)) return "unchanged";
        if(!ourNfc(current)) return "kept "+current;
        if(!nfcServiceExists(target)) return "missing "+target;
        putNfc(target);
        return target.equals(nfc()) ? "set "+target : "failed";
    }
    private static String markerValue() {
        String m=System.getenv("POWERWALLET_MARKER");
        if(m==null || m.isEmpty()) {
            Properties p=new Properties();
            try(FileInputStream in=new FileInputStream(STATE)){p.load(in);m=p.getProperty("marker");}catch(Exception ignored){}
        }
        if(m==null || m.isEmpty()) m="google_pay";
        if(!m.matches("[a-z_]{1,40}")) throw new IllegalArgumentException("Invalid marker");
        return m;
    }
    private final PressDetector detector=new PressDetector();
    private volatile boolean running=true;
    private volatile String last="尚未触发";
    private volatile int launches=0;
    private int appUid;
    private String original, inputPath, mode;
    private Process reader;
    private LocalServerSocket server;
    private Object activityManager;
    private volatile Object companionProvider;
    private final IBinder providerToken=new Binder();
    private final Binder control=new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if(code<1 || code>3) return super.onTransact(code,data,reply,flags);
            int caller=Binder.getCallingUid();
            if(caller!=appUid && caller!=2000) throw new SecurityException("Untrusted caller");
            data.enforceInterface("cn.haiyu.powerwallet.Control");
            try {
                String value=code==2 ? (restore()?"已停用，并恢复原快捷键":"已停用，保留当前新快捷键") : code==3 ? setMode(data.readString()) : status();
                reply.writeNoException(); reply.writeString(value);
                if(code==2) timer.schedule(() -> shutdown(),100,TimeUnit.MILLISECONDS);
            } catch(Exception e) {reply.writeException(new IllegalStateException(e));}
            return true;
        }
    };
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) {
        try { execute(args); }
        catch(Exception failure) {
            System.err.println("ERROR "+failure.getClass().getSimpleName()+": "+failure.getMessage());
            System.exit(1);
        }
    }
    private static void execute(String[] args) throws Exception {
        if(args.length>1 && "--mode".equals(args[0])) {
            try(LocalSocket s=new LocalSocket()) {
                s.connect(new LocalSocketAddress(MainActivity.SOCKET)); s.setSoTimeout(4000);
                s.getOutputStream().write(("MODE "+args[1]+"\n").getBytes(StandardCharsets.UTF_8));
                String reply=new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
                if(reply==null) throw new IOException("Helper closed without a response");
                System.out.println(reply);
                if(!reply.startsWith("已激活")) System.exit(1);
            }
            return;
        }
        if(args.length>0 && "--handoff".equals(args[0])) {
            // Re-activation: stop the running helper WITHOUT restoring, so the backup stays authoritative.
            try(LocalSocket s=new LocalSocket()) {
                s.connect(new LocalSocketAddress(MainActivity.SOCKET)); s.setSoTimeout(4000);
                s.getOutputStream().write("HANDOFF\n".getBytes(StandardCharsets.UTF_8));
                String reply=new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
                System.out.println(reply);
                if(reply==null || !reply.startsWith("已交接")) System.exit(1); // Older helper: caller falls back to --stop.
            }
            return;
        }
        if(args.length>0 && ("--stop".equals(args[0]) || "--status".equals(args[0]) || "--restore".equals(args[0]))) {
            boolean restoring="--restore".equals(args[0]);
            try(LocalSocket s=new LocalSocket()) {
                s.connect(new LocalSocketAddress(MainActivity.SOCKET)); s.setSoTimeout(4000);
                s.getOutputStream().write(("--status".equals(args[0])?"STATUS\n":"STOP\n").getBytes(StandardCharsets.UTF_8));
                String reply=new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
                if(reply==null) throw new IOException("Helper closed without a response");
                System.out.println(reply);
            } catch(IOException unavailable) {
                if(!restoring) throw unavailable;
                restoreNfc();
                if(!ours(setting())) {System.out.println("已停用，保留当前快捷键");return;}
                Properties backup=new Properties();
                try(FileInputStream in=new FileInputStream(STATE)){backup.load(in);}
                String previous=backup.getProperty("original");
                if(previous==null) throw new IOException("No original shortcut backup; no changes made");
                putSetting(previous);
                System.out.println("已停用，并恢复原快捷键");
            }
            return;
        }
        Helper helper=new Helper();
        try { helper.run(); }
        catch(Exception failure) {
            if(helper.original!=null) try{helper.restore();}catch(Exception rollback){System.err.println("RESTORE_ERROR "+rollback);}
            helper.shutdown();
            throw failure;
        }
    }
    private static String command(String... args) throws IOException, InterruptedException {
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
        while((n=p.getInputStream().read(b))!=-1) out.write(b,0,n);
        int code=p.waitFor(); String value=out.toString("UTF-8").trim();
        if(code!=0) throw new IOException("Command failed: "+args[0]+" "+value);
        return value;
    }
    private static String setting() throws Exception {return command("/system/bin/settings","--user","0","get","system",KEY);}
    private static void putSetting(String value) throws Exception {
        if("null".equals(value)) command("/system/bin/settings","--user","0","delete","system",KEY);
        else command("/system/bin/settings","--user","0","put","system",KEY,value);
        if(!value.equals(setting())) throw new IOException("Shortcut setting did not persist");
    }
    private void run() throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("ADB activation required");
        String build=command("/system/bin/getprop","ro.product.device")+"|"+command("/system/bin/getprop","ro.build.version.incremental");
        if(!Arrays.asList(VERIFIED).contains(build) && !"1".equals(System.getenv("POWERWALLET_ALLOW_UNVERIFIED")))
            throw new IOException("Unverified build "+build+"; set POWERWALLET_ALLOW_UNVERIFIED=1 after checking the marker is a no-op");
        server=new LocalServerSocket(MainActivity.SOCKET); // Exclusive instance before any changes.
        String packages=command("/system/bin/cmd","package","list","packages","-U", "cn.haiyu.powerwallet");
        Matcher uid=Pattern.compile("package:cn\\.haiyu\\.powerwallet uid:(\\d+)").matcher(packages);
        if(!uid.find()) throw new IOException("Install the companion app first");
        appUid=Integer.parseInt(uid.group(1));
        inputPath=findPowerDevice();
        Properties props=new Properties();
        if(STATE.exists()) try(FileInputStream in=new FileInputStream(STATE)){props.load(in);}
        original=props.getProperty("original");
        String current=setting();
        // HyperOS resets unrecognised values (our google_pay marker) to null at boot; keep the backup then.
        if(original==null || (!ours(current) && !"null".equals(current))) original=current;
        mode=MI.equals(props.getProperty("mode")) ? MI : MARKER;
        props.setProperty("original",original); props.setProperty("device",inputPath); props.setProperty("marker",MARKER);
        props.setProperty("mode",mode);
        String currentNfc=nfc();
        if(props.getProperty("nfcOriginal")==null || !ourNfc(currentNfc)) props.setProperty("nfcOriginal",currentNfc);
        try(FileOutputStream out=new FileOutputStream(STATE)){props.store(out,"PowerWallet reversible shortcut backup");}
        STATE.setReadable(false,false); STATE.setReadable(true,true); STATE.setWritable(false,false); STATE.setWritable(true,true);
        reader=new ProcessBuilder("/system/bin/getevent","-lt",inputPath).redirectErrorStream(true).start();
        Thread.sleep(100);
        if(!reader.isAlive()) throw new IOException("Power-key input is unavailable");
        System.out.println("NFC "+applyNfc(mode));
        putSetting(mode); // MARKER is a no-op on validated ROMs, consuming the system wallet shortcut; MI keeps it native.
        bindProvider();
        new Thread(() -> socketLoop(),"wallet-control").start();
        timer.scheduleAtFixedRate(() -> {
            if(running && detector.poll(SystemClock.uptimeMillis())) launch();
        },20,20,TimeUnit.MILLISECONDS);
        System.out.println("READY uid="+appUid+" input="+inputPath+" original="+original+" mode="+mode);
        Pattern event=Pattern.compile("\\[\\s*([0-9]+\\.[0-9]+)\\]\\s+(?:/dev/input/event\\d+:\\s+)?EV_KEY\\s+KEY_POWER\\s+(DOWN|UP)");
        try(BufferedReader in=new BufferedReader(new InputStreamReader(reader.getInputStream()))) {
            String line;
            while(running && (line=in.readLine())!=null) {
                Matcher m=event.matcher(line);
                if(m.find()) detector.event("DOWN".equals(m.group(2)),Math.round(Double.parseDouble(m.group(1))*1000));
            }
        } finally {
            if(running) restore();
            shutdown();
        }
    }
    private void bindProvider() throws Exception {
        Class<?> amClass=Class.forName("android.app.IActivityManager");
        activityManager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object holder=amClass.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
            .invoke(activityManager,"cn.haiyu.powerwallet.bridge",0,providerToken,"PowerWallet");
        if(holder==null) throw new IOException("Companion provider is unavailable");
        Object provider=holder.getClass().getField("provider").get(holder);
        companionProvider=provider;
        Bundle args=new Bundle(); args.putBinder("helper",control);
        AttributionSource attribution=new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        Class<?> cp=Class.forName("android.content.IContentProvider");
        Object result=cp.getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class)
            .invoke(provider,attribution,"cn.haiyu.powerwallet.bridge","bind",null,args);
        if(!(result instanceof Bundle) || !((Bundle)result).getBoolean("ok")) throw new IOException("Companion rejected the connection");
        IBinder providerBinder=(IBinder)cp.getMethod("asBinder").invoke(provider);
        providerBinder.linkToDeath(() -> {
            if(running) timer.schedule(() -> { try{bindProvider();}catch(Exception e){System.out.println("REBIND_ERROR "+e);} },1,TimeUnit.SECONDS);
        },0);
        System.out.println("BRIDGE_READY");
    }
    private String status() throws Exception {
        String v=setting();
        String head=MARKER.equals(v) ? "已激活 · 双击打开 Google 钱包" : MI.equals(v) ? "已激活 · 双击打开小米钱包（系统原生）" : "快捷键已改变 · 请重新激活";
        String n=nfc();
        String pay=GOOGLE_NFC.equals(n)?"Google 钱包":MI_NFC.equals(n)?"小米钱包":"其他应用（未改动）";
        return head+"|默认付款：" +pay+"|已触发 "+launches+" 次 · "+last;
    }
    private synchronized String setMode(String requested) throws Exception {
        String target="google".equals(requested) ? MARKER : "mi".equals(requested) ? MI : null;
        if(target==null) throw new IllegalArgumentException("Unknown mode");
        if(!ours(setting())) return status(); // Respect a setting the user changed elsewhere.
        putSetting(target); mode=target;
        System.out.println("NFC "+applyNfc(target));
        Properties props=new Properties();
        try(FileInputStream in=new FileInputStream(STATE)){props.load(in);}
        props.setProperty("mode",target);
        try(FileOutputStream out=new FileOutputStream(STATE)){props.store(out,"PowerWallet reversible shortcut backup");}
        System.out.println("MODE "+target);
        return status();
    }
    private String findPowerDevice() throws Exception {
        String desc=command("/system/bin/getevent","-lp");
        Matcher devices=Pattern.compile("add device \\d+: (/dev/input/event\\d+)(.*?)(?=add device|\\z)",Pattern.DOTALL).matcher(desc);
        String fallback=null;
        while(devices.find()) {
            String details=devices.group(2);
            if(details.contains("KEY_POWER")) {
                if(details.contains("\"pmic_pwrkey\"")) return devices.group(1);
                if(!details.contains("bark") && !details.contains("goodix")) fallback=devices.group(1);
            }
        }
        if(fallback!=null) return fallback;
        throw new IOException("No physical power key found");
    }
    private void launch() {
        try {
            String v=setting();
            if(MI.equals(v)) return; // System opens Mi Pay natively.
            if(!MARKER.equals(v)) {last="快捷键已被系统或用户更改，请重新激活"; return;}
            command("/system/bin/input","keyevent","KEYCODE_WAKEUP");
            Bundle before=keyguardState();
            boolean repaired=dismissAuthenticatedKeyguard(before);
            startWallet();
            // Facial recognition can finish after the second press or while Wallet starts.
            // Retry only while Keyguard is still showing and authentication is done.
            for(int attempt=0;attempt<4;attempt++) {
                Thread.sleep(180);
                Bundle current=keyguardState();
                if(!current.getBoolean("showing")) break;
                if(dismissAuthenticatedKeyguard(current)) {
                    startWallet(); repaired=true; break;
                }
            }
            launches++; last="已发送钱包启动请求";
            Bundle after=keyguardState();
            System.out.println("LAUNCH "+launches+" before="+stateText(before)+" after="+stateText(after)+" dismiss="+repaired);
        } catch(Exception e) {last="启动失败，请检查钱包及调试授权"; System.out.println("LAUNCH_ERROR "+e);}
    }
    private void startWallet() throws Exception {
        String result=command("/system/bin/am","start","--user","0","-W","-a","com.google.android.apps.wallet.main.QUICKDRAW","-p",MainActivity.WALLET);
        if(result.contains("Error:") || result.contains("Exception")) throw new IOException(result);
    }
    private Bundle keyguardState() throws Exception {
        AttributionSource attribution=new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        return (Bundle)Class.forName("android.content.IContentProvider")
            .getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class)
            .invoke(companionProvider,attribution,"cn.haiyu.powerwallet.bridge","keyguard",null,null);
    }
    private boolean dismissAuthenticatedKeyguard(Bundle state) throws Exception {
        if(!state.getBoolean("showing") || state.getBoolean("deviceLocked")) return false;
        command("/system/bin/wm","dismiss-keyguard");
        for(int i=0;i<10;i++) {
            Thread.sleep(35);
            if(!keyguardState().getBoolean("showing")) return true;
        }
        System.out.println("KEYGUARD_DISMISS_PENDING");
        return false;
    }
    private String stateText(Bundle state) {
        return "showing:"+state.getBoolean("showing")+",locked:"+state.getBoolean("deviceLocked");
    }
    private void socketLoop() {
        while(running) {
            try(LocalSocket socket=server.accept()) {
                socket.setSoTimeout(1500);
                int peer=socket.getPeerCredentials().getUid();
                if(peer!=appUid && peer!=2000) continue;
                String cmd=new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
                String reply;
                if("STATUS".equals(cmd)) reply=status();
                else if("STOP".equals(cmd)) reply=restore() ? "已停用，并恢复原快捷键" : "已停用，保留当前新快捷键";
                else if(cmd!=null && cmd.startsWith("MODE ")) reply=setMode(cmd.substring(5).trim());
                else if("HANDOFF".equals(cmd)) reply="已交接";
                else reply="Unsupported request";
                socket.getOutputStream().write((reply+"\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                if("STOP".equals(cmd) || "HANDOFF".equals(cmd)) {shutdown(); return;}
            } catch(Exception e) {if(running) System.out.println("CONTROL_ERROR "+e);}
        }
    }
    private synchronized boolean restore() throws Exception {
        restoreNfc();
        if(!ours(setting())) return false;
        String backup=null;
        try(FileInputStream in=new FileInputStream(STATE)){Properties p=new Properties();p.load(in);backup=p.getProperty("original");}catch(Exception ignored){}
        putSetting(backup!=null?backup:original); return true;
    }
    private static void restoreNfc() {
        try {
            Properties p=new Properties();
            try(FileInputStream in=new FileInputStream(STATE)){p.load(in);}
            String original=p.getProperty("nfcOriginal");
            if(original!=null && ourNfc(nfc()) && !original.equals(nfc())) putNfc(original);
        } catch(Exception e) {System.out.println("NFC_RESTORE_ERROR "+e);}
    }
    private void shutdown() {
        running=false; timer.shutdownNow();
        if(reader!=null) reader.destroy();
        try{server.close();}catch(Exception ignored){}
        try{Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternal",String.class,IBinder.class).invoke(activityManager,"cn.haiyu.powerwallet.bridge",providerToken);}catch(Exception ignored){}
    }
}
