package com.omarea.gesture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Choreographer;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.omarea.gesture.Gesture;
import com.omarea.gesture.remote.RemoteAPI;
import com.omarea.gesture.ui.SideGestureBar;
import com.omarea.gesture.ui.QuickPanel;
import com.omarea.gesture.util.GlobalState;
import com.omarea.gesture.util.Recents;
import com.omarea.gesture.SpfConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class AccessibilityServiceGesture extends AccessibilityService {
    public Recents recents = new Recents();
    private SideGestureBar floatVitualTouchBar = null;
    private BroadcastReceiver configChanged = null;
    private BroadcastReceiver serviceDisable = null;
    private BroadcastReceiver screenStateReceiver;
    private SharedPreferences appSwitchBlackList;
    private BatteryReceiver batteryReceiver;

    private static boolean lastIsLight = false;
    private static long lastTime = System.currentTimeMillis();
    private static final double allTime = 500.0;
    private int lastCol = 0x0;
    private boolean rending = false;
    private boolean running = false;
    private Runnable f = new Runnable() {
                    @Override
                    public void run() {
                        if (RemoteAPI.updateBarAutoColor()) {
                            if (!lastIsLight) lastTime = System.currentTimeMillis();
                            lastIsLight = true;
                        } else {
                            if (lastIsLight) lastTime = System.currentTimeMillis();
                            lastIsLight = false;
                        }

                        running = false;
                    }
                };
    private Handler handler = Gesture.handler;
    private Runnable periodicTask = new Runnable() {
        @Override
        public void run() {
            if (!running && !rending) {
                running = true;
                new Thread(f).start();
            }

            handler.postDelayed(this, rending ? 50 : (int)allTime);
        }
    };
    private Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            int color = getBarColor();
            GlobalState.iosBarColor = color;
            if (GlobalState.updateBar != null) GlobalState.updateBar.run();
            
            rending = lastCol != color;
            lastCol = color;

            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    
    public static int getBarColor() {
        if (lastIsLight) {
            double a = Math.min(1.0, (System.currentTimeMillis() - lastTime) / allTime);
            int c = (int)Math.floor(70+(1.0-a)*165.0);
            return (0xFF << 24) | ((c & 0xFF) << 16) | ((c & 0xFF) << 8) | (c & 0xFF);
        } else {
            double a = Math.min(1.0, (System.currentTimeMillis() - lastTime) / allTime);
            int c = (int)Math.floor(70+a*165.0);
            return (0xFF << 24) | ((c & 0xFF) << 16) | ((c & 0xFF) << 8) | (c & 0xFF);
        }
    }

    private boolean removeGestureView() {
        if (floatVitualTouchBar != null) {
            floatVitualTouchBar.removeGestureView();
            floatVitualTouchBar = null;
            return true;
        }
        return false;
    }

    private boolean ignored(String packageName) {
        return recents.inputMethods.contains(packageName);
    }

    // 检测应用是否是可以打开的
    private boolean canOpen(String packageName) {
        if (recents.blackList.contains(packageName)) {
            return false;
        } else if (recents.whiteList.contains(packageName)) {
            return true;
        } else {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                recents.whiteList.add(packageName);
                return true;
            } else {
                recents.blackList.add(packageName);
                return false;
            }
        }
    }

    // 启动器应用（桌面）
    private ArrayList<String> getLauncherApps() {
        Intent resolveIntent = new Intent(Intent.ACTION_MAIN, null);
        resolveIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveinfoList = getPackageManager().queryIntentActivities(resolveIntent, 0);
        ArrayList<String> launcherApps = new ArrayList<>();
        for (ResolveInfo resolveInfo : resolveinfoList) {
            String packageName = resolveInfo.activityInfo.packageName;
            if (!("com.android.settings".equals(packageName))) { // MIUI的设置也算个桌面，什么鬼
                launcherApps.add(packageName);
            }
        }
        return launcherApps;
    }

    // 输入法应用
    private ArrayList<String> getInputMethods() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        ArrayList<String> inputMethods = new ArrayList<>();
        for (InputMethodInfo inputMethodInfo : imm.getInputMethodList()) {
            inputMethods.add(inputMethodInfo.getPackageName());
        }
        return inputMethods;
    }

    private List<String> colorPolingApps = null; // 允许轮询颜色的APP
    private long lastOriginEventTime = 0L;

    private ArrayList<Integer> blackTypeList = new ArrayList<Integer>() {{
        add(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY);
        add(AccessibilityWindowInfo.TYPE_INPUT_METHOD);
        add(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER);
        add(AccessibilityWindowInfo.TYPE_SYSTEM);
    }};

    // TODO:判断是否进入全屏状态，以便在游戏和视频过程中降低功耗
    @Override
    public void onAccessibilityEvent(final AccessibilityEvent event) {
    }

    private void setBatteryReceiver() {
        if (batteryReceiver == null) {
            batteryReceiver = new BatteryReceiver(this);
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_POWER_DISCONNECTED));
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        setServiceInfo();

        if (appSwitchBlackList == null) {
            appSwitchBlackList = getSharedPreferences(SpfConfig.AppSwitchBlackList, Context.MODE_PRIVATE);
        }

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point point = new Point();
        wm.getDefaultDisplay().getRealSize(point);
        GlobalState.displayWidth = point.x;
        GlobalState.displayHeight = point.y;
        GlobalState.consecutive = Gesture.config.getBoolean(SpfConfig.IOS_BAR_CONSECUTIVE, SpfConfig.IOS_BAR_CONSECUTIVE_DEFAULT);

        GlobalState.useBatteryCapacity = Gesture.config.getBoolean(SpfConfig.IOS_BAR_POP_BATTERY, SpfConfig.IOS_BAR_POP_BATTERY_DEFAULT);
        if (GlobalState.useBatteryCapacity) {
            setBatteryReceiver();
        }

        if (configChanged == null) {
            configChanged = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    GlobalState.consecutive = Gesture.config.getBoolean(SpfConfig.IOS_BAR_CONSECUTIVE, SpfConfig.IOS_BAR_CONSECUTIVE_DEFAULT);
                    GlobalState.useBatteryCapacity = Gesture.config.getBoolean(SpfConfig.IOS_BAR_POP_BATTERY, SpfConfig.IOS_BAR_POP_BATTERY_DEFAULT);
                    if (GlobalState.useBatteryCapacity) {
                        setBatteryReceiver();
                    } else if (batteryReceiver != null) {
                        unregisterReceiver(batteryReceiver);
                        batteryReceiver = null;
                    }

                    String action = intent != null ? intent.getAction() : null;
                    if (action != null && action.equals(getString(R.string.app_switch_changed))) {
                        if (recents != null) {
                            recents.clear();
                            Gesture.toast("OK！", Toast.LENGTH_SHORT);
                        }
                    } else {
                        new AdbProcessExtractor().updateAdbProcessState(context, false);
                        if (action != null && action.equals(getString(R.string.action_adb_process))) {
                            if (GlobalState.enhancedMode) {
                                setResultCode(0);
                                setResultData("Nice, The enhancement mode has been activated ^_^");
                            } else {
                                setResultCode(5);
                                setResultData("Unable to start enhanced mode >_<");
                            }
                        }
                        createPopupView(false);
                    }
                }
            };

            registerReceiver(configChanged, new IntentFilter(getString(R.string.action_config_changed)));
            registerReceiver(configChanged, new IntentFilter(getString(R.string.app_switch_changed)));
            registerReceiver(configChanged, new IntentFilter(getString(R.string.action_adb_process)));
        }
        if (serviceDisable == null) {
            serviceDisable = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        disableSelf();
                    }
                    stopSelf();
                }
            };
            registerReceiver(serviceDisable, new IntentFilter(getString(R.string.action_service_disable)));
        }
        createPopupView(false);

        registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        }
        registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        registerReceiver(screenStateReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));

        Collections.addAll(recents.blackList, getResources().getStringArray(R.array.app_switch_black_list));

        new AdbProcessExtractor().updateAdbProcessState(this, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String results = RemoteAPI.getColorPollingApps();
                if (results != null) {
                    colorPolingApps = Arrays.asList(results.split("\n"));
                    Gesture.config.edit().putString("color_polling_apps", results).apply();
                    setServiceInfo();
                } else {
                    colorPolingApps = Arrays.asList(Gesture.config.getString("color_polling_apps", "").split("\n"));
                }
            }
        }).start();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        removeGestureView();
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    // 监测屏幕旋转
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (floatVitualTouchBar != null && newConfig != null) {
            // 关闭常用应用面板
            QuickPanel.close();

            GlobalState.isLandscapf = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;

            // 如果分辨率变了，那就重新创建手势区域
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            Point point = new Point();
            wm.getDefaultDisplay().getRealSize(point);
            if (point.x != GlobalState.displayWidth || point.y != GlobalState.displayHeight) {
                GlobalState.displayWidth = point.x;
                GlobalState.displayHeight = point.y;
                createPopupView(true);
            }
        }
    }

    private void createPopupView(boolean delayed) {
        final AccessibilityServiceGesture context = this;

        new android.os.Handler().postDelayed(new Runnable(){
            @Override
            public void run() {
                removeGestureView();
                setServiceInfo();
                floatVitualTouchBar = new SideGestureBar(context);
            }
        }, (delayed ? 500 : 0));
    }

    private void setServiceInfo() {
        AccessibilityServiceInfo accessibilityServiceInfo = getServiceInfo();
        // accessibilityServiceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        // accessibilityServiceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if ((!Gesture.config.getBoolean(SpfConfig.LOW_POWER_MODE, SpfConfig.LOW_POWER_MODE_DEFAULT)) && colorPolingApps != null && colorPolingApps.size() > 0) {
            // accessibilityServiceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            accessibilityServiceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        } else {
            // accessibilityServiceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            accessibilityServiceInfo.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        }
        setServiceInfo(accessibilityServiceInfo);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Choreographer.getInstance().postFrameCallback(frameCallback);
        handler.post(periodicTask);
    }

    @Override
    public void onDestroy() {
        if (floatVitualTouchBar != null) {
            floatVitualTouchBar.removeGestureView();
        }

        if (configChanged != null) {
            unregisterReceiver(configChanged);
            configChanged = null;
        }

        if (screenStateReceiver != null) {
            unregisterReceiver(screenStateReceiver);
            screenStateReceiver = null;
        }

        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
            batteryReceiver = null;
        }
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        handler.removeCallbacks(periodicTask);
        // stopForeground(true);
        super.onDestroy();
    }
}
