/*
 * Copyright (C) 2006-2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocalPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import static android.provider.Settings.System.END_BUTTON_BEHAVIOR;

import com.android.internal.policy.PolicyManager;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationListener;
import android.view.RawInputEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import android.view.WindowManagerImpl;
import android.view.WindowManagerPolicy;
import android.view.WindowManagerPolicy.WindowState;
import android.view.animation.Animation;
import android.media.IAudioService;
import android.media.AudioManager;

import java.util.Observable;
import java.util.Observer;

/**
 * WindowManagerPolicy implementation for the WIMM UI.
 */
public class WimmWindowManager implements WindowManagerPolicy {
    private static final String TAG = "WimmWindowManager";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    
    private static final int WALLPAPER_LAYER = 0;
    private static final int APPLICATION_LAYER = 1;
    private static final int PHONE_LAYER = 2;
    private static final int SEARCH_BAR_LAYER = 3;
    private static final int STATUS_BAR_PANEL_LAYER = 4;
    private static final int TOAST_LAYER = 5;
    private static final int STATUS_BAR_LAYER = 6;
    private static final int PRIORITY_PHONE_LAYER = 7;
    private static final int SYSTEM_ALERT_LAYER = 8;
    private static final int SYSTEM_ERROR_LAYER = 9;
    // the keyguard; nothing on top of these can take focus, since they are
    // responsible for power management when displayed.
    private static final int KEYGUARD_LAYER = 10;
    private static final int KEYGUARD_DIALOG_LAYER = 11;
    // things in here CAN NOT take focus, but are shown on top of everything else.
    private static final int SYSTEM_OVERLAY_LAYER = 12;

    static final int APPLICATION_MEDIA_SUBLAYER = -2;
    static final int APPLICATION_MEDIA_OVERLAY_SUBLAYER = -1;
    static final int APPLICATION_PANEL_SUBLAYER = 1;
    static final int APPLICATION_SUB_PANEL_SUBLAYER = 2;
    
    static public final String SYSTEM_DIALOG_REASON_KEY = "reason";
    static public final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";

    private Context mContext;
    private IWindowManager mWindowManager;

    private GlobalActions mGlobalActions;
    private Handler mHandler;
    
    private int mW, mH;
    private int mCurLeft, mCurTop, mCurRight, mCurBottom;
    
    static final Rect mTmpParentFrame = new Rect();
    static final Rect mTmpDisplayFrame = new Rect();
    static final Rect mTmpVisibleFrame = new Rect();

    private PowerManager.WakeLock mBroadcastWakeLock;

    Runnable mPowerLongPress = new Runnable() {
        public void run() {
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
            showGlobalActionsDialog();
        }
    };

    private void showGlobalActionsDialog() {
        mGlobalActions.showDialog();
    }
    
    /** {@inheritDoc} */
    public void init(Context context, IWindowManager windowManager,
            LocalPowerManager powerManager) {
        mContext = context;
        mWindowManager = windowManager;
        mHandler = new Handler();
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mBroadcastWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "WimmWindowManager.mBroadcastWakeLock");
        mGlobalActions = new GlobalActions(mContext, powerManager);
    }

    /** {@inheritDoc} */
    public int checkAddPermission(WindowManager.LayoutParams attrs) {
        int type = attrs.type;
        if (type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                || type > WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
            return WindowManagerImpl.ADD_OKAY;
        }
        String permission = null;
        switch (type) {
            case TYPE_TOAST:
                // XXX right now the app process has complete control over
                // this...  should introduce a token to let the system
                // monitor/control what they are doing.
                break;
            case TYPE_PHONE:
            case TYPE_PRIORITY_PHONE:
            case TYPE_SYSTEM_ALERT:
            case TYPE_SYSTEM_ERROR:
            case TYPE_SYSTEM_OVERLAY:
                permission = android.Manifest.permission.SYSTEM_ALERT_WINDOW;
                break;
            default:
                permission = android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
        }
        if (permission != null) {
            if (mContext.checkCallingOrSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return WindowManagerImpl.ADD_PERMISSION_DENIED;
            }
        }
        return WindowManagerImpl.ADD_OKAY;
    }
    
    public void adjustWindowParamsLw(WindowManager.LayoutParams attrs) {
        switch (attrs.type) {
            case TYPE_SYSTEM_OVERLAY:
            case TYPE_TOAST:
                // These types of windows can't receive input events.
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                break;
        }
    }
    
    /** {@inheritDoc} */
    public void adjustConfigurationLw(Configuration config) {
    }
    
    public boolean isCheekPressedAgainstScreen(MotionEvent ev) {
        return false;
    }
    
    /** {@inheritDoc} */
    public int windowTypeToLayerLw(int type) {
        if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            return APPLICATION_LAYER;
        }
        switch (type) {
        case TYPE_APPLICATION_PANEL:
            return APPLICATION_LAYER;
        case TYPE_APPLICATION_SUB_PANEL:
            return APPLICATION_LAYER;
        case TYPE_STATUS_BAR:
            return STATUS_BAR_LAYER;
        case TYPE_STATUS_BAR_PANEL:
            return STATUS_BAR_PANEL_LAYER;
        case TYPE_SEARCH_BAR:
            return SEARCH_BAR_LAYER;
        case TYPE_PHONE:
            return PHONE_LAYER;
        case TYPE_KEYGUARD:
            return KEYGUARD_LAYER;
        case TYPE_KEYGUARD_DIALOG:
            return KEYGUARD_DIALOG_LAYER;
        case TYPE_SYSTEM_ALERT:
            return SYSTEM_ALERT_LAYER;
        case TYPE_SYSTEM_ERROR:
            return SYSTEM_ERROR_LAYER;
        case TYPE_SYSTEM_OVERLAY:
            return SYSTEM_OVERLAY_LAYER;
        case TYPE_PRIORITY_PHONE:
            return PRIORITY_PHONE_LAYER;
        case TYPE_TOAST:
            return TOAST_LAYER;
        case TYPE_WALLPAPER:
            return WALLPAPER_LAYER;
        }
        Log.e(TAG, "Unknown window type: " + type);
        return APPLICATION_LAYER;
    }

    /** {@inheritDoc} */
    public int subWindowTypeToLayerLw(int type) {
        switch (type) {
        case TYPE_APPLICATION_PANEL:
            return APPLICATION_PANEL_SUBLAYER;
        case TYPE_APPLICATION_MEDIA:
            return APPLICATION_MEDIA_SUBLAYER;
        case TYPE_APPLICATION_MEDIA_OVERLAY:
            return APPLICATION_MEDIA_OVERLAY_SUBLAYER;
        case TYPE_APPLICATION_SUB_PANEL:
            return APPLICATION_SUB_PANEL_SUBLAYER;
        }
        Log.e(TAG, "Unknown sub-window type: " + type);
        return 0;
    }

    public int getMaxWallpaperLayer() {
        return STATUS_BAR_LAYER;
    }

    public boolean doesForceHide(WindowState win, WindowManager.LayoutParams attrs) {
        return attrs.type == WindowManager.LayoutParams.TYPE_KEYGUARD;
    }
    
    public boolean canBeForceHidden(WindowState win, WindowManager.LayoutParams attrs) {
        return attrs.type != WindowManager.LayoutParams.TYPE_STATUS_BAR
                && attrs.type != WindowManager.LayoutParams.TYPE_WALLPAPER;
    }
    
    /** {@inheritDoc} */
    public View addStartingWindow(IBinder appToken, String packageName,
                                  int theme, CharSequence nonLocalizedLabel,
                                  int labelRes, int icon) {
        return null;
    }

    /** {@inheritDoc} */
    public void removeStartingWindow(IBinder appToken, View window) {
    }

    public int prepareAddWindowLw(WindowState win, WindowManager.LayoutParams attrs) {
        return WindowManagerImpl.ADD_OKAY;
    }

    public void removeWindowLw(WindowState win) {
    }

    public int selectAnimationLw(WindowState win, int transit) {
        return 0;
    }

    public Animation createForceHideEnterAnimation() {
        return null;
    }
    
    private static IAudioService getAudioInterface() {
        return IAudioService.Stub.asInterface(ServiceManager.checkService(Context.AUDIO_SERVICE));
    }

    /** {@inheritDoc} */
    public boolean interceptKeyTi(WindowState win, int code, int metaKeys, boolean down, 
            int repeatCount, int flags) {
  
        if ( localLOGV ) Log.v(TAG, "interceptKeyTi: code=" + code
            + " metaKeys=" + metaKeys
            + " down=" + down
            + " repeatCount=" + repeatCount
            + " flags=" + flags
            );

        return false;
    }

    public void getContentInsetHintLw(WindowManager.LayoutParams attrs, Rect coveredInset) {
        final int fl = attrs.flags;
        
        if ((fl &
                (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
            coveredInset.set(mCurLeft, mCurTop, mW - mCurRight, mH - mCurBottom);
        } else {
            coveredInset.setEmpty();
        }
    }
    
    /** {@inheritDoc} */
    public void beginLayoutLw(int displayWidth, int displayHeight) {
        mW = displayWidth;
        mH = displayHeight;
        mCurLeft = 0;
        mCurTop = 0;
        mCurRight = displayWidth;
        mCurBottom = displayHeight;
    }

    /** {@inheritDoc} */
    public void layoutWindowLw(WindowState win, WindowManager.LayoutParams attrs, WindowState attached) {
        final int fl = attrs.flags;
        
        final Rect pf = mTmpParentFrame;
        final Rect df = mTmpDisplayFrame;
        final Rect vf = mTmpVisibleFrame;
        
        if ((fl &
                (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
            df.left = 0;
            df.top = 0;
            df.right = mW;
            df.bottom = mH;
            vf.left = mCurLeft;
            vf.top = mCurTop;
            vf.right = mCurRight;
            vf.bottom = mCurBottom;
        } else if ((fl & FLAG_LAYOUT_IN_SCREEN) == 0) {
            // Make sure this window doesn't intrude into the status bar.
            df.left = vf.left = mCurLeft;
            df.top = vf.top = mCurTop;
            df.right = vf.right = mCurRight;
            df.bottom = vf.bottom = mCurBottom;
        } else {
            df.left = vf.left = 0;
            df.top = vf.top = 0;
            df.right = vf.right = mW;
            df.bottom = vf.bottom = mH;
        }
        
        if (attached != null && (fl & (FLAG_LAYOUT_IN_SCREEN)) == 0) {
            pf.set(attached.getFrameLw());
        } else {
            pf.set(df);
        }
        
        if ((fl & FLAG_LAYOUT_NO_LIMITS) != 0) {
            df.left = df.top = vf.left = vf.top = -10000;
            df.right = df.bottom = vf.right = vf.bottom = 10000;
        }

        win.computeFrameLw(pf, df, vf, vf);
    }

    /** {@inheritDoc} */
    public int finishLayoutLw() {
        return 0;
    }

    /** {@inheritDoc} */
    public void beginAnimationLw(int displayWidth, int displayHeight) {
    }

    /** {@inheritDoc} */
    public void animatingWindowLw(WindowState win, WindowManager.LayoutParams attrs) {
    }

    /** {@inheritDoc} */
    public boolean finishAnimationLw() {
       return false;
    }

    /** {@inheritDoc} */
    public boolean preprocessInputEventTq(RawInputEvent event) {
        return false;
    }
    
    /** {@inheritDoc} */
    public boolean isAppSwitchKeyTqTiLwLi(int keycode) {
        return false;
    }
    
    /** {@inheritDoc} */
    public boolean isMovementKeyTi(int keycode) {
        return false;
    }

    /**
     * @return Whether music is being played right now.
     */
    private boolean isMusicActive() {
        final AudioManager am = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            Log.w(TAG, "isMusicActive: couldn't get AudioManager reference");
            return false;
        }
        return am.isMusicActive();
    }

    /**
     * Tell the audio service to adjust the volume appropriate to the event.
     * @param keycode
     */
    private void sendVolToMusic(int keycode) {
        final IAudioService audio = getAudioInterface();
        if (audio == null) {
            Log.w(TAG, "sendVolToMusic: couldn't get IAudioService reference");
            return;
        }
        try {
            // since audio is playing, we shouldn't have to hold a wake lock
            // during the call, but we do it as a precaution for the rare possibility
            // that the music stops right before we call this
            mBroadcastWakeLock.acquire();
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                keycode == KeyEvent.KEYCODE_VOLUME_UP
                            ? AudioManager.ADJUST_RAISE
                            : AudioManager.ADJUST_LOWER,
                    0);
        } catch (RemoteException e) {
            Log.w(TAG, "IAudioService.adjustStreamVolume() threw RemoteException " + e);
        } finally {
            mBroadcastWakeLock.release();
        }
    }

    public int interceptKeyTq(RawInputEvent event, boolean screenIsOn) {
        int result = ACTION_PASS_TO_USER | ACTION_POKE_USER_ACTIVITY;
        final boolean isWakeKey = isWakeKeyTq(event);
        
        int type = event.type;
        int code = event.keycode;
        boolean down = event.value != 0;
        
        if ( localLOGV ) Log.v(TAG, "interceptKeyTq:" 
            + " when=" + event.when
            + " type=" + type 
            + " code=" + code 
            + " down=" + down 
            + " screenIsOn=" + screenIsOn 
            + " isWakeKey=" + isWakeKey
        );
            
        if (type == RawInputEvent.EV_KEY) {
            if (code == KeyEvent.KEYCODE_POWER) {
                
                if ( screenIsOn ) {
                    if ( down ) {
                        if ( localLOGV ) Log.v(TAG, "interceptKeyTq: scheduling global window");
                        mHandler.postDelayed(mPowerLongPress,
                                ViewConfiguration.getGlobalActionKeyTimeout());
                    } else {
                        if ( localLOGV ) Log.v(TAG, "interceptKeyTq: un-scheduling global window");
                        mHandler.removeCallbacks(mPowerLongPress);
                    }
                    
                } else if ( localLOGV ) Log.v(TAG, "interceptKeyTq: screen-off");
            }
        }
        
        return result;
    }
    
    /** {@inheritDoc} */
    public boolean isWakeRelMovementTq(int device, int classes,
            RawInputEvent event) {
        // if it's tagged with one of the wake bits, it wakes up the device
        return ((event.flags & (FLAG_WAKE | FLAG_WAKE_DROPPED)) != 0);
    }

    /** {@inheritDoc} */
    public boolean isWakeAbsMovementTq(int device, int classes,
            RawInputEvent event) {
	//
	// NJV - 20111216 force EV_ABS events from touchpanel to bring device to active mode
	// MT Touch events range from keycode 0x30 (ABS_MT_TOUCH_MAJOR) to 0x39 (ABS_MT_TRACKING_ID)
	//
	if ((event.keycode >= 0x30) && (event.keycode <= 0x39))
		event.flags |= FLAG_WAKE;

	// if it's tagged with one of the wake bits, it wakes up the device
        return ((event.flags & (FLAG_WAKE | FLAG_WAKE_DROPPED)) != 0);
    }

    /**
     * Given the current state of the world, should this key wake up the device?
     */
    protected boolean isWakeKeyTq(RawInputEvent event) {
        // There are not key maps for trackball devices, but we'd still
        // like to have pressing it wake the device up, so force it here.
        int keycode = event.keycode;
        int flags = event.flags;
        if (keycode == RawInputEvent.BTN_MOUSE) {
            flags |= WindowManagerPolicy.FLAG_WAKE;
        }
        return (flags
                & (WindowManagerPolicy.FLAG_WAKE | WindowManagerPolicy.FLAG_WAKE_DROPPED)) != 0;
    }

    /** {@inheritDoc} */
    public void screenTurnedOff(int why) {
        EventLog.writeEvent(70000, 0);
    }

    /** {@inheritDoc} */
    public void screenTurnedOn() {
        EventLog.writeEvent(70000, 1);
    }

    /** {@inheritDoc} */
    public void enableKeyguard(boolean enabled) {
    }

    /** {@inheritDoc} */
    public void exitKeyguardSecurely(OnKeyguardExitResult callback) {
    }

    /** {@inheritDoc} */
    public boolean keyguardIsShowingTq() {
        return false;
    }

    /** {@inheritDoc} */
    public boolean inKeyguardRestrictedKeyInputMode() {
        return false;
    }

    /**
     * Callback from {@link KeyguardViewMediator}
     */
    public void onKeyguardShow() {
    }

    private void sendCloseSystemWindows() {
        sendCloseSystemWindows(null);
    }

    private void sendCloseSystemWindows(String reason) {
        try {
            ActivityManagerNative.getDefault().closeSystemDialogs(reason);
        } catch (RemoteException e) {
        }
    }

    public int rotationForOrientationLw(int orientation, int lastRotation,
            boolean displayEnabled) {
        // get rid of rotation for now. Always rotation of 0
        return Surface.ROTATION_0;
    }
    
    public boolean detectSafeMode() {
        return false;
    }
    
    /** {@inheritDoc} */
    public void systemReady() {
        android.os.SystemProperties.set("dev.bootcomplete", "1");
    }
    
    /** {@inheritDoc} */
    public void enableScreenAfterBoot() {
        updateRotation();
    }
    
    private void updateRotation() {
        int rotation = Surface.ROTATION_0;
        try {
            //set orientation on WindowManager
            mWindowManager.setRotation(rotation, true, 0);
        } catch (RemoteException e) {}
    }
    
    public void setCurrentOrientationLw(int newOrientation) {
    }
    
    public boolean performHapticFeedbackLw(WindowState win, int effectId, boolean always) {
        return false;
    }
    
    public void keyFeedbackFromInput(KeyEvent event) {
    }
    
    public void screenOnStoppedLw() {
    }

    public boolean allowKeyRepeat() {
        return true;
    }
}
