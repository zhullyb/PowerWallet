package cn.haiyu.powerwallet;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs a shell command as the adb shell user by connecting to this phone's own wireless
 * debugging service with the key paired once through the notification. Blocking: never
 * call on the main thread.
 */
public final class SelfAdb {
    public static final String PAIRING="_adb-tls-pairing._tcp", CONNECT="_adb-tls-connect._tcp";
    static final String LOOPBACK="127.0.0.1";
    private SelfAdb() {}

    public static boolean paired(Context c) {return c.getSharedPreferences("wireless",Context.MODE_PRIVATE).getBoolean("paired",false);}
    static void setPaired(Context c, boolean v) {c.getSharedPreferences("wireless",Context.MODE_PRIVATE).edit().putBoolean("paired",v).apply();}
    static AdbKey key(Context c) throws Exception {return AdbKey.load(new File(c.getFilesDir(),"adb"));}

    public static synchronized String run(Context c, String command) throws Exception {
        if(!paired(c)) throw new IOException("尚未配对无线调试，请先点「配对无线调试」");
        boolean enabledHere=false;
        if(!wifiDebuggingOn(c)) {
            if(!setWifiDebugging(c,true)) throw new IOException("请开启「无线调试」后重试");
            enabledHere=true;
        }
        try {
            // mDNS registration right after switching wireless debugging on can take a while.
            Integer port=findPort(c,CONNECT,enabledHere?20000:8000);
            if(port==null && enabledHere) port=findPort(c,CONNECT,10000);
            if(port==null) throw new IOException("未找到无线调试服务，请确认已连接 Wi-Fi");
            try {return AdbClient.shell(key(c),LOOPBACK,port,command);}
            catch(IOException e) {
                if(String.valueOf(e.getMessage()).contains("密钥")) setPaired(c,false);
                throw e;
            }
        } finally {
            if(enabledHere) setWifiDebugging(c,false);
        }
    }

    static boolean wifiDebuggingOn(Context c) {return Settings.Global.getInt(c.getContentResolver(),"adb_wifi_enabled",0)==1;}
    static boolean setWifiDebugging(Context c, boolean on) {
        if(c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")!=PackageManager.PERMISSION_GRANTED) return false;
        try {return Settings.Global.putInt(c.getContentResolver(),"adb_wifi_enabled",on?1:0);}
        catch(Exception e) {return false;}
    }

    /** mDNS lookup of this device's adb service; returns a port reachable on loopback. */
    @SuppressWarnings("deprecation")
    public static Integer findPort(Context c, String type, long timeoutMs) throws InterruptedException {
        final NsdManager nsd=c.getSystemService(NsdManager.class);
        final Set<Integer> ports=new CopyOnWriteArraySet<>();
        final java.util.concurrent.ExecutorService resolver=java.util.concurrent.Executors.newSingleThreadExecutor();
        NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener() {
            public void onServiceFound(final NsdServiceInfo info) {
                resolver.execute(() -> {
                    final CountDownLatch done=new CountDownLatch(1);
                    try {
                        nsd.resolveService(info,new NsdManager.ResolveListener() {
                            public void onResolveFailed(NsdServiceInfo s, int e) {done.countDown();}
                            public void onServiceResolved(NsdServiceInfo s) {if(isLocal(s.getHost())) ports.add(s.getPort()); done.countDown();}
                        });
                        done.await(4,TimeUnit.SECONDS);
                    } catch(Exception ignored) {}
                });
            }
            public void onServiceLost(NsdServiceInfo i) {}
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onStartDiscoveryFailed(String t, int e) {}
            public void onStopDiscoveryFailed(String t, int e) {}
        };
        try {
            nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);
            long end=System.currentTimeMillis()+timeoutMs;
            while(System.currentTimeMillis()<end) {
                for(Integer p:ports) if(reachable(p)) return p; else ports.remove(p);
                Thread.sleep(250);
            }
            return null;
        } finally {
            try{nsd.stopServiceDiscovery(listener);}catch(Exception ignored){}
            resolver.shutdownNow();
        }
    }
    private static boolean isLocal(InetAddress host) {
        try {
            if(host==null) return false;
            for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces()))
                for(InetAddress a:Collections.list(ni.getInetAddresses())) if(a.equals(host)) return true;
        } catch(Exception ignored) {}
        return false;
    }
    private static boolean reachable(int port) {
        try(Socket s=new Socket()) {s.connect(new InetSocketAddress(LOOPBACK,port),800);return true;}
        catch(Exception e) {return false;}
    }
}
