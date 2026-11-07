package com.tungsten.fcl.vr;

import android.app.Activity;
import android.widget.Toast;

import com.tungsten.fcl.R;
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog;

public final class QuestVrCompatibilityWarning {
    private QuestVrCompatibilityWarning() {}

    public static void showFirstLaunch(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()
                || !QuestVrFeatures.needsInitialNotice(activity)) return;
        new FCLAlertDialog.Builder(activity)
                .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                .setTitle(activity.getString(R.string.settings_vr_compatibility_title))
                .setMessage(activity.getString(R.string.settings_vr_compatibility_initial))
                .setPositiveButton(activity.getString(R.string.dialog_positive), null)
                .create().show();
        QuestVrFeatures.markInitialNoticeShown(activity);
    }

    public static void confirmEnable(Activity activity, Runnable refresh) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (QuestVrFeatures.isEnabled(activity)) {
            refresh.run();
            return;
        }
        FCLAlertDialog dialog = new FCLAlertDialog.Builder(activity)
                .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                .setTitle(activity.getString(R.string.settings_vr_compatibility_title))
                .setMessage(activity.getString(R.string.settings_vr_compatibility_enable))
                .setPositiveButton(activity.getString(R.string.settings_vr_enable_anyway), () -> {
                    if (!QuestVrFeatures.setEnabled(activity, true)) {
                        Toast.makeText(activity, R.string.settings_vr_save_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(activity.getString(R.string.button_cancel), null)
                .create();
        dialog.setOnDismissListener(ignored -> refresh.run());
        dialog.show();
        QuestVrFeatures.markInitialNoticeShown(activity);
    }
}
