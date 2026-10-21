package com.zmk.tvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the remote when the box boots, so the phone page works without opening the app. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) RemoteService.start(ctx);
    }
}
