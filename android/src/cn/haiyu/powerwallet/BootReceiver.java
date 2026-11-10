package cn.haiyu.powerwallet;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * HyperOS clears launch_QAW_wallet at boot. Schedule ReapplyJob with an unmetered (Wi-Fi)
 * network constraint: wireless debugging only works on Wi-Fi, so if there is none at boot
 * the system runs the job as soon as Wi-Fi connects.
 */
public final class BootReceiver extends BroadcastReceiver {
    static final int JOB_ID=42;
    @Override public void onReceive(Context c, Intent intent) {
        if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if(!NativeMode.GOOGLE.equals(NativeMode.mode(c))) return;
        schedule(c);
    }
    static void schedule(Context c) {
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(c,ReapplyJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build();
        try {c.getSystemService(JobScheduler.class).schedule(job);}
        catch(RuntimeException e) {android.util.Log.e("PowerWallet","cannot schedule boot reapply: "+e);}
    }
}
