package org.koreader.backgroundonyxsynckoreader.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.koreader.backgroundonyxsynckoreader.service.SyncService;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SyncService.start(context);
        }
    }
}
