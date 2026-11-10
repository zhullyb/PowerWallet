package cn.haiyu.powerwallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    public static final String SOCKET = "cn.haiyu.powerwallet.v1";
    public static final String WALLET = "com.google.android.apps.walletnfcrel";
    private TextView status;
    private boolean busy;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(244,247,242));
        getWindow().setNavigationBarColor(Color.rgb(244,247,242));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(244,247,242));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(48), dp(28), dp(36)); scroll.addView(root);
        text(root, "POWER × 2", 13, true, 0xFF466E5A);
        text(root, "电源键钱包", 32, true, 0xFF173B32);
        TextView intro = text(root, "双击电源键，打开 Google 钱包。", 17, false, 0xFF446052);
        intro.setPadding(0, dp(12), 0, dp(28));
        status = text(root, "正在检查…", 16, true, 0xFF173B32);
        status.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFFE0EDE2); bg.setCornerRadius(dp(18)); status.setBackground(bg);
        button(root, "打开 Google 钱包", new View.OnClickListener() { public void onClick(View v) { openWallet(); }});
        TextView modeLabel = text(root, "双击电源键打开", 14, true, 0xFF466E5A);
        modeLabel.setPadding(0, dp(22), 0, 0);
        button(root, "Google 钱包", new View.OnClickListener() { public void onClick(View v) { status.setText("正在切换…"); request("MODE google"); }});
        button(root, "小米钱包", new View.OnClickListener() { public void onClick(View v) { status.setText("正在切换…"); request("MODE mi"); }});
        button(root, "配对无线调试（首次设置）", new View.OnClickListener() { public void onClick(View v) { wireless(); }});
        button(root, "刷新状态", new View.OnClickListener() { public void onClick(View v) { status.setText("正在读取…"); request("REFRESH"); }});
        button(root, "恢复原设置", new View.OnClickListener() { public void onClick(View v) {
            new AlertDialog.Builder(MainActivity.this).setTitle("恢复原设置？")
                .setMessage("把双击电源键和默认付款应用恢复为本应用第一次修改前的值。")
                .setPositiveButton("恢复", (d,w) -> request("STOP")).setNegativeButton("取消", null).show();
        }});
        TextView help = text(root, "使用说明\n\n首次使用：点「配对无线调试」，按提示在通知栏输入配对码。之后切换模式不需要电脑，但需要连接 Wi-Fi（应用会临时打开无线调试，完成后关闭）。\n\nHyperOS 每次开机会清除 Google 钱包的双击设置，应用会在开机并连上 Wi-Fi 后自动写回。请在应用设置中允许「自启动」，省电策略设为「无限制」。\n\n小米钱包模式由系统原生保存，重启不受影响。要长期使用小米钱包，请在本应用中切换（系统设置里改成小米钱包会在重启后被改回 Google 钱包）；改成其他功能（如相机）不会被覆盖。\n\n锁屏时请按钱包要求完成验证。本应用不读取银行卡，也不处理付款。", 14, false, 0xFF536357);
        help.setPadding(0, dp(26), 0, 0);
        setContentView(scroll);
    }
    @Override protected void onResume() { super.onResume(); request("STATUS"); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout root, String value, int size, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(3), 1); if (bold) t.setTypeface(null, Typeface.BOLD); root.addView(t); return t;
    }
    private void button(LinearLayout root, String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(0xFF173B32);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56)); p.topMargin = dp(12);
        root.addView(b,p); b.setOnClickListener(listener);
    }
    private void wireless() {
        if(android.os.Build.VERSION.SDK_INT<30) { new AlertDialog.Builder(this).setMessage("无线调试需要 Android 11 及以上。").setPositiveButton("知道了",null).show(); return; }
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1); return;
        }
        WirelessService.start(this);
        new AlertDialog.Builder(this).setTitle("配对无线调试")
            .setMessage("1. 连接 Wi-Fi，打开 开发者选项 → 无线调试。\n2. 点「使用配对码配对设备」，保持弹窗打开。\n3. 下拉通知栏，在「电源键钱包」通知里输入 6 位配对码。\n\n只需配对一次。")
            .setPositiveButton("打开开发者选项", (d,w) -> openDeveloperOptions()).setNegativeButton("关闭", null).show();
    }
    private void openDeveloperOptions() {
        Intent i=new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle args=new Bundle(); args.putString(":settings:fragment_args_key","toggle_adb_wireless");
        i.putExtra(":settings:fragment_args_key","toggle_adb_wireless").putExtra(":settings:show_fragment_args",args);
        try { startActivity(i); } catch(Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if(code==1) { if(r.length>0 && r[0]==android.content.pm.PackageManager.PERMISSION_GRANTED) wireless();
            else new AlertDialog.Builder(this).setMessage("需要通知权限才能在通知栏输入配对码。").setPositiveButton("知道了",null).show(); }
    }
    private void openWallet() {
        try { startActivity(new Intent("com.google.android.apps.wallet.main.QUICKDRAW").setPackage(WALLET)); }
        catch (Exception first) {
            try { Intent i = getPackageManager().getLaunchIntentForPackage(WALLET); if(i == null) throw first; startActivity(i); }
            catch(Exception e) { new AlertDialog.Builder(this).setMessage("无法打开 Google 钱包，请检查是否已经安装并启用。").setPositiveButton("知道了",null).show(); }
        }
    }
    private void request(final String command) {
        if(busy) return; busy = true;
        new Thread(() -> {
            String reply;
            // Native mode: shell writes via the phone's own wireless debugging (see SelfAdb).
            try {
                if(command.startsWith("MODE ")) reply=NativeMode.apply(this,"MODE google".equals(command));
                else if("STOP".equals(command)) reply=NativeMode.restore(this);
                else if("REFRESH".equals(command)) reply=NativeMode.refresh(this);
                else reply=NativeMode.status(this);
            } catch(Exception e) {reply="操作失败："+e.getMessage()+"\n\n"+NativeMode.status(this);}
            final String result=reply;
            runOnUiThread(() -> { busy = false; status.setText(result); });
        }, "wallet-status").start();
    }
}
