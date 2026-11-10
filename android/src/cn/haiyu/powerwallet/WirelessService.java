package cn.haiyu.powerwallet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-time setup without a computer: pairs with the phone's own adbd over wireless
 * debugging (pairing code typed into this notification), grants WRITE_SECURE_SETTINGS
 * through the resulting shell, then applies the chosen double-press mode.
 */
public final class WirelessService extends Service {
    public static final String START="cn.haiyu.powerwallet.WIRELESS_START", CODE="cn.haiyu.powerwallet.WIRELESS_CODE",
        CANCEL="cn.haiyu.powerwallet.WIRELESS_CANCEL", KEY_CODE="code";
    private static final String CHANNEL="wireless";
    private static final int ID=1;

    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean busy, waitingForCode;

    public static void start(Context c) {
        Intent i=new Intent(c,WirelessService.class).setAction(START);
        if(Build.VERSION.SDK_INT>=26) c.startForegroundService(i); else c.startService(i);
    }
    @Override public IBinder onBind(Intent i) {return null;}

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel ch=new NotificationChannel(CHANNEL,"无线调试配对",NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("输入无线调试配对码并显示设置结果"); ch.setSound(null,null);
        nm.createNotificationChannel(ch);
    }

    static void log(String m) {android.util.Log.i("PowerWallet",m);}
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action=intent==null?START:intent.getAction();
        if(START.equals(action)) {
            foreground(progress("正在准备…","正在搜索手机的无线调试服务"));
            if(!busy) {busy=true; worker.execute(this::autoActivate);}
        } else if(CODE.equals(action)) {
            Bundle results=RemoteInput.getResultsFromIntent(intent);
            CharSequence code=results==null?null:results.getCharSequence(KEY_CODE);
            if(code!=null && waitingForCode) {
                waitingForCode=false;
                final String value=code.toString().replaceAll("\\s","");
                notifyNow(progress("正在配对…","配对码 "+value));
                worker.execute(() -> pairThenActivate(value));
            }
        } else if(CANCEL.equals(action)) finish(null,null);
        return START_NOT_STICKY;
    }

    // ---------- flow ----------
    private void autoActivate() {
        try {
            if(SelfAdb.paired(this)) {
                try {setup();return;}
                catch(Exception e) {
                    log("setup with existing pairing failed "+e);
                    if(SelfAdb.paired(this)) {finish("设置失败",message(e));return;} // Not a key problem.
                }
            }
            askForCode("需要配对一次","开发者选项 → 无线调试：打开开关，点「使用配对码配对设备」并保持弹窗打开，然后在这里输入 6 位配对码。");
        } catch(Throwable e) {log("autoActivate error "+android.util.Log.getStackTraceString(e));finish("设置失败",message(e));}
    }
    private void pairThenActivate(String code) {
        try {
            Integer pairPort=SelfAdb.findPort(this,SelfAdb.PAIRING,8000);
            if(pairPort==null) {askForCode("未找到配对服务","请确认「使用配对码配对设备」的弹窗仍然打开，然后重新输入配对码。");return;}
            AdbClient.pair(SelfAdb.key(this),SelfAdb.LOOPBACK,pairPort,code,"PowerWallet@"+Build.MODEL.replace(' ','_'));
            SelfAdb.setPaired(this,true);
            notifyNow(progress("配对成功，正在设置…","连接无线调试服务"));
            setup();
        } catch(Exception e) {
            log("pair error "+android.util.Log.getStackTraceString(e));
            if(message(e).contains("配对")) askForCode("配对失败",message(e)+"。请重新输入当前显示的配对码。");
            else finish("设置失败",message(e));
        }
    }
    /** Grants the permission used to toggle wireless debugging later, then applies the chosen mode. */
    private void setup() throws Exception {
        SelfAdb.run(this,"pm grant "+getPackageName()+" android.permission.WRITE_SECURE_SETTINGS");
        String status=NativeMode.apply(this,!NativeMode.MI.equals(NativeMode.mode(this)));
        finish("双击电源键已就绪",status);
    }

    // ---------- notifications ----------
    private void askForCode(String title, String text) {
        waitingForCode=true;
        RemoteInput input=new RemoteInput.Builder(KEY_CODE).setLabel("6 位配对码").build();
        PendingIntent reply=PendingIntent.getService(this,1,new Intent(this,WirelessService.class).setAction(CODE),
            PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
        Notification.Action action=new Notification.Action.Builder(null,"输入配对码",reply).addRemoteInput(input).build();
        Notification.Builder b=base(title,text).addAction(action).addAction(cancelAction()).setOngoing(true);
        notifyNow(b.build());
    }
    private Notification progress(String title, String text) {
        return base(title,text).setProgress(0,0,true).setOngoing(true).addAction(cancelAction()).build();
    }
    private Notification.Action cancelAction() {
        PendingIntent cancel=PendingIntent.getService(this,2,new Intent(this,WirelessService.class).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null,"取消",cancel).build();
    }
    private Notification.Builder base(String title, String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(getApplicationInfo().icon).setContentTitle(title)
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(false);
    }
    private void foreground(Notification n) {
        if(Build.VERSION.SDK_INT>=34) startForeground(ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID,n);
    }
    private void notifyNow(Notification n) {getSystemService(NotificationManager.class).notify(ID,n);}
    private void finish(String title, String text) {
        waitingForCode=false;
        if(Build.VERSION.SDK_INT>=24) stopForeground(title==null?STOP_FOREGROUND_REMOVE:STOP_FOREGROUND_DETACH);
        if(title!=null) notifyNow(base(title,text).setAutoCancel(true).build());
        else getSystemService(NotificationManager.class).cancel(ID);
        stopSelf();
    }
    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static String message(Throwable e) {
        String m=e.getMessage();
        return m==null||m.isEmpty()?e.getClass().getSimpleName():m;
    }
}
