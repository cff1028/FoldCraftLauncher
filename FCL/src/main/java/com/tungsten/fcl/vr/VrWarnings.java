package com.tungsten.fcl.vr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.LinearLayoutCompat;

import com.mio.util.AndroidUtilKt;
import com.tungsten.fcl.R;
import com.tungsten.fcl.ui.download.QuestVivecraftDownloadPage;
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog;
import com.tungsten.fcllibrary.component.view.FCLButton;
import com.tungsten.fcllibrary.util.ConvertUtils;

public final class VrWarnings {
    public static final String VIVECRAFT_DOWNLOAD_URL =
            "https://github.com/QuestCraftPlusPlus/VivecraftMod/releases";

    private VrWarnings() {}

    public static int messageFor(VivecraftInspector.Status status) {
        return switch (status) {
            case INCOMPATIBLE -> R.string.settings_vr_vivecraft_incompatible;
            case CONFLICT -> R.string.settings_vr_vivecraft_conflict;
            case FAILED -> R.string.settings_vr_vivecraft_scan_failed;
            default -> R.string.settings_vr_vivecraft_missing;
        };
    }

    public static void show(Context context, VivecraftInspector.Status status,
                            QuestVivecraftTarget target, Runnable onDismiss) {
        boolean[] browseRequested = {false};
        FCLAlertDialog dialog = new FCLAlertDialog.Builder(context)
                .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                .setTitle(context.getString(R.string.settings_vr_requirements_title))
                .setMessage(context.getString(messageFor(status)))
                .setPositiveButton(context.getString(R.string.dialog_positive), null)
                .setNeutralButton(context.getString(R.string.qv_download_browse), () -> browseRequested[0] = true)
                .setNegativeButton(context.getString(R.string.settings_vr_vivecraft_download),
                        () -> AndroidUtilKt.openLink(context, VIVECRAFT_DOWNLOAD_URL))
                .create();
        stackWarningButtons(dialog);
        dialog.setOnDismissListener(ignored -> {
            if (onDismiss != null) onDismiss.run();
            if (browseRequested[0]) {
                new Handler(Looper.getMainLooper()).post(() -> QuestVivecraftDownloadPage.open(target));
            }
        });
        dialog.show();
    }

    private static void stackWarningButtons(FCLAlertDialog dialog) {
        FCLButton positive = dialog.findViewById(R.id.positive);
        LinearLayoutCompat actions = (LinearLayoutCompat) positive.getParent();
        actions.setOrientation(LinearLayoutCompat.VERTICAL);
        boolean hasVisibleButton = false;
        for (int id : new int[]{R.id.positive, R.id.neutral, R.id.extra, R.id.negative}) {
            FCLButton button = dialog.findViewById(id);
            LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (hasVisibleButton && button.getVisibility() != View.GONE) {
                params.topMargin = ConvertUtils.dip2px(dialog.getContext(), 4);
            }
            button.setLayoutParams(params);
            button.setSingleLine(false);
            button.setMaxLines(Integer.MAX_VALUE);
            button.setHorizontallyScrolling(false);
            button.setEllipsize(null);
            button.setMinHeight(ConvertUtils.dip2px(dialog.getContext(), 48));
            hasVisibleButton |= button.getVisibility() != View.GONE;
        }
    }
}
