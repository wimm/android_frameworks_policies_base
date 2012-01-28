package com.android.internal.policy.impl;

import com.android.internal.app.ShutdownThread;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.LocalPowerManager;


class GlobalActions {

	// if this changes, update android_6442/vendor/WIMM/NotificationAgent/src/com/wimm/notification/GlobalActionsController.java
	private static final String ACTION_SHOW_GLOBAL_DIALOG = "com.wimm.action.SHOW_GLOBAL_DIALOG";
	private static final String ACTION_HIDE_GLOBAL_DIALOG = "com.wimm.action.HIDE_GLOBAL_DIALOG";
	
	// if this changes, update android_6442/vendor/WIMM/WimmFramework/java/com/wimm/framework/android/SystemBridge.java
	private static final String ACTION_INITIATE_SHUTDOWN = "com.wimm.action.INITIATE_SHUTDOWN";
	private static final String ACTION_ENABLE_USER_ACTIVITY = "com.wimm.action.ENABLE_USER_ACTIVITY";
	private static final String EXTRA_ENABLE = "enable";

    private final Context mContext;
    private final LocalPowerManager mPowerManager;

    public GlobalActions(Context context, LocalPowerManager powerManager) {
        mContext = context;
        mPowerManager = powerManager;

        context.registerReceiver(
            mUserActivityReceiver, 
            new IntentFilter(ACTION_ENABLE_USER_ACTIVITY),
            android.Manifest.permission.DISABLE_KEYGUARD,
            null
        );

        context.registerReceiver(
            mShutdownReceiver, 
            new IntentFilter(ACTION_INITIATE_SHUTDOWN),
            android.Manifest.permission.REBOOT,
            null
        );

        context.registerReceiver(
            mDialogReceiver, 
            new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        );
    }

    public void showDialog() {
        mContext.startService(new Intent(ACTION_SHOW_GLOBAL_DIALOG));
    }
    
    private BroadcastReceiver mUserActivityReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            boolean enabled = intent.getBooleanExtra(EXTRA_ENABLE, true);
            mPowerManager.enableUserActivity(enabled);
        }
    };
    
    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            ShutdownThread.shutdown(context, false);
        }
    };
    
    private BroadcastReceiver mDialogReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra(WimmWindowManager.SYSTEM_DIALOG_REASON_KEY);
            if (!WimmWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                context.startService(new Intent(ACTION_HIDE_GLOBAL_DIALOG));
            }
        }
    };
}
