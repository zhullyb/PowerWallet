package cn.haiyu.powerwallet;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Writes Google mode back after boot via SelfAdb; retried with backoff on failure. */
public final class ReapplyJob extends JobService {
    private volatile Thread worker;
    @Override public boolean onStartJob(final JobParameters params) {
        worker=new Thread(() -> {
            boolean retry=false;
            try {
                Thread.sleep(3000); // Let Wi-Fi and the boot-time shortcut cleanup settle.
                NativeMode.reapplyAfterBoot(this);
            } catch(Exception e) {
                android.util.Log.w("PowerWallet","boot reapply failed: "+e);
                retry=SelfAdb.paired(this); // Unpaired keys need the user; don't spin.
            }
            jobFinished(params,retry);
        },"powerwallet-reapply");
        worker.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Thread t=worker; if(t!=null) t.interrupt();
        return true;
    }
}
