package com.tungsten.fcl.vr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Locale;

public final class QuestVrFeatures {
    private static final String ENABLED_KEY = "questVrAllowUnsupportedDevice";
    private static final String NOTICE_KEY = "questVrCompatibilityNoticeShown";

    private QuestVrFeatures() {}

    public static boolean isQuestDevice() {
        return isQuestDevice(Build.MANUFACTURER, Build.BRAND, Build.MODEL);
    }

    static boolean isQuestDevice(String manufacturer, String brand, String model) {
        if (!isMetaBrand(manufacturer) && !isMetaBrand(brand)) return false;
        String name = normalize(model);
        if (name.startsWith("oculus ")) name = name.substring(7).trim();
        else if (name.startsWith("meta ")) name = name.substring(5).trim();
        return name.equals("quest") || name.startsWith("quest ")
                || name.startsWith("quest-")
                || name.length() > 5 && name.startsWith("quest") && Character.isDigit(name.charAt(5));
    }

    private static boolean isMetaBrand(String value) {
        String brand = normalize(value);
        return brand.equals("oculus") || brand.equals("meta");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return isQuestDevice() || preferences(context).getBoolean(ENABLED_KEY, false);
    }

    public static boolean isVrEnabled(Context context, boolean requested) {
        return requested && isEnabled(context);
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        if (isQuestDevice()) return true;
        SharedPreferences preferences = preferences(context);
        boolean previous = preferences.getBoolean(ENABLED_KEY, false);
        if (preferences.edit().putBoolean(ENABLED_KEY, enabled).commit()) return true;
        preferences.edit().putBoolean(ENABLED_KEY, previous).apply();
        return false;
    }

    public static boolean needsInitialNotice(Context context) {
        return !isQuestDevice() && !preferences(context).getBoolean(NOTICE_KEY, false);
    }

    public static void markInitialNoticeShown(Context context) {
        preferences(context).edit().putBoolean(NOTICE_KEY, true).apply();
    }

    public static void requireEnabled(Context context) {
        if (!isEnabled(context)) throw new IllegalStateException("VR features are disabled on this device");
    }
}
