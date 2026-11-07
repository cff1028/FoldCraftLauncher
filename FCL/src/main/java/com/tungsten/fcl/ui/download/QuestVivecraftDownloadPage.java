package com.tungsten.fcl.ui.download;

import android.content.Context;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.mio.data.ModsChanged;
import com.mio.util.AndroidUtilKt;
import com.tungsten.fcl.R;
import com.tungsten.fcl.activity.MainActivity;
import com.tungsten.fcl.ui.UIManager;
import com.tungsten.fcl.vr.QuestVivecraftService;
import com.tungsten.fcl.vr.QuestVivecraftTarget;
import com.tungsten.fclcore.task.Schedulers;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog;
import com.tungsten.fcllibrary.component.ui.FCLPage;
import com.tungsten.fcllibrary.component.view.FCLSpinner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class QuestVivecraftDownloadPage extends FCLPage {
    private final QuestVivecraftTarget target;
    private final QuestVivecraftService service = new QuestVivecraftService();
    private final List<QuestVivecraftService.ReleaseAsset> releases = new ArrayList<>();
    private final List<QuestVivecraftService.InstalledMod> installed = new ArrayList<>();
    private final ReleaseAdapter adapter = new ReleaseAdapter();
    private final TextView status;
    private final ProgressBar progress;
    private final View refresh;
    private final View cancel;
    private final FCLSpinner<QuestVivecraftService.Source> sourceSpinner;
    private QuestVivecraftService.Source source = QuestVivecraftService.Source.QUESTCRAFT;
    private QuestVivecraftService.Cancellation cancellation;
    private boolean busy;
    private boolean detached;

    public static void open(QuestVivecraftTarget target) {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.refreshMenuView(null);
        activity.binding.download.setSelected(true);
        DownloadUI ui = UIManager.getInstance().getDownloadUI();
        UIManager.getInstance().switchUI(ui);
        ui.showDownloadPage(DownloadUI.PAGE_ID_DOWNLOAD_MOD);
        ui.showTempPage(new QuestVivecraftDownloadPage(activity, target));
    }

    public QuestVivecraftDownloadPage(Context context, QuestVivecraftTarget target) {
        super(context, PAGE_ID_TEMP, R.layout.page_quest_vivecraft);
        this.target = target;
        status = findViewById(R.id.qv_status);
        progress = findViewById(R.id.qv_progress);
        refresh = findViewById(R.id.qv_refresh);
        cancel = findViewById(R.id.qv_cancel);
        sourceSpinner = findViewById(R.id.qv_source);
        sourceSpinner.setItems(List.of(QuestVivecraftService.Source.values()));
        sourceSpinner.setOnItemSelectedListener((index, selected) -> {
            if (busy || detached) {
                sourceSpinner.setSelection(source.ordinal());
                return;
            }
            if (source != selected) {
                source = selected;
                load();
            }
        });
        TextView destination = findViewById(R.id.qv_target);
        destination.setText(context.getString(R.string.qv_download_target, target.instanceId,
                target.minecraftVersion.isEmpty() ? "?" : target.minecraftVersion,
                target.loader.name() + " " + target.loaderVersion,
                new File(target.gameDirectory, "mods").getAbsolutePath()));
        ((ListView) findViewById(R.id.qv_list)).setAdapter(adapter);
        refresh.setOnClickListener(v -> load());
        cancel.setOnClickListener(v -> {
            if (cancellation != null) cancellation.cancel();
            cancel.setEnabled(false);
            status.setText(R.string.qv_download_cancelling);
        });
        findViewById(R.id.qv_website).setOnClickListener(v ->
                AndroidUtilKt.openLink(context, source.releasesUrl));
        findViewById(R.id.qv_back).setOnClickListener(v -> UIManager.getInstance().getDownloadUI().dismissCurrentTempPage());
        getContentView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                boolean returning = detached;
                detached = false;
                if (returning && !busy) load();
            }
            @Override public void onViewDetachedFromWindow(View v) {
                detached = true;
                if (cancellation != null) cancellation.cancel();
            }
        });
        load();
    }

    private void setBusy(boolean value) {
        busy = value;
        refresh.setEnabled(!value);
        sourceSpinner.setEnabled(!value);
        cancel.setEnabled(true);
        cancel.setVisibility(value ? View.VISIBLE : View.GONE);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void load() {
        if (busy || detached) return;
        releases.clear();
        adapter.notifyDataSetChanged();
        if (target.loader == QuestVivecraftTarget.Loader.NONE || target.minecraftVersion.isEmpty()
                || target.loaderVersion.isEmpty()) {
            status.setText(R.string.qv_download_unsupported);
            return;
        }
        cancellation = new QuestVivecraftService.Cancellation();
        QuestVivecraftService.Cancellation operation = cancellation;
        QuestVivecraftService.Source requestedSource = source;
        setBusy(true);
        progress.setIndeterminate(true);
        status.setText(R.string.qv_download_loading);
        CompletableFuture.supplyAsync(() -> {
            try {
                List<QuestVivecraftService.InstalledMod> mods = service.scanInstalled(target, operation);
                List<QuestVivecraftService.ReleaseAsset> assets = service.fetchReleases(target, requestedSource, operation);
                return new Listing(mods, assets);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }).whenCompleteAsync((listing, error) -> {
            if (getActivity().isDestroyed() || operation != cancellation) return;
            setBusy(false);
            if (detached || source != requestedSource) return;
            if (error != null) {
                showError(error);
                return;
            }
            installed.clear(); installed.addAll(listing.mods);
            releases.clear(); releases.addAll(listing.assets);
            showListingStatus();
            adapter.notifyDataSetChanged();
        }, Schedulers.androidUIThread());
    }

    private void showListingStatus() {
        if (!installed.isEmpty()) {
            String names = String.join(", ", installed.stream().map(mod -> mod.file.getName()).toArray(String[]::new));
            status.setText(getContext().getString(installed.size() == 1 && installed.get(0).compatible
                    ? R.string.qv_download_installed : R.string.qv_download_conflict, names));
        } else {
            status.setText(releases.isEmpty() ? R.string.qv_download_empty : R.string.qv_download_choose);
        }
    }

    private void confirmInstall(QuestVivecraftService.ReleaseAsset asset) {
        if (busy || detached || asset.source != source) return;
        new FCLAlertDialog.Builder(getContext())
                .setTitle(getContext().getString(R.string.qv_download_install_title))
                .setMessage(getContext().getString(R.string.qv_download_install_prompt,
                        asset.fileName, target.instanceId, new File(target.gameDirectory, "mods").getAbsolutePath(),
                        asset.source.displayName))
                .setPositiveButton(getContext().getString(R.string.button_download), () -> install(asset))
                .setNegativeButton(getContext().getString(R.string.button_cancel), null).create().show();
    }

    private void install(QuestVivecraftService.ReleaseAsset asset) {
        if (busy || detached || asset.source != source) return;
        cancellation = new QuestVivecraftService.Cancellation();
        QuestVivecraftService.Cancellation operation = cancellation;
        setBusy(true);
        progress.setIndeterminate(true);
        status.setText(R.string.qv_download_downloading);
        File cache = new File(getContext().getCacheDir(), "quest-vivecraft");
        CompletableFuture.supplyAsync(() -> {
            try {
                final long[] lastUpdate = {0};
                final QuestVivecraftService.Phase[] previous = {null};
                QuestVivecraftService.InstallResult result = service.install(target, asset, cache, operation,
                        (phase, completed, total) -> {
                            long now = SystemClock.uptimeMillis();
                            if (phase == previous[0] && completed != total && now - lastUpdate[0] < 200) return;
                            previous[0] = phase; lastUpdate[0] = now;
                            Schedulers.androidUIThread().execute(() -> {
                                if (!detached && busy && operation == cancellation) showProgress(phase, completed, total);
                            });
                        });
                if (!result.alreadyInstalled) ModsChanged.notifyChanged();
                return result;
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }).whenCompleteAsync((result, error) -> {
            if (getActivity().isDestroyed() || operation != cancellation) return;
            setBusy(false);
            if (detached) return;
            if (error != null) {
                showError(error);
                return;
            }
            installed.clear(); installed.add(result.mod);
            status.setText(getContext().getString(result.alreadyInstalled
                    ? R.string.qv_download_already : R.string.qv_download_success, result.mod.file.getName()));
            adapter.notifyDataSetChanged();
        }, Schedulers.androidUIThread());
    }

    private void showProgress(QuestVivecraftService.Phase phase, long done, long total) {
        if (phase == QuestVivecraftService.Phase.DOWNLOADING) {
            progress.setIndeterminate(total <= 0);
            if (total > 0) progress.setProgress((int) Math.min(1000, done * 1000 / total));
            status.setText(getContext().getString(R.string.qv_download_progress, size(done), total > 0 ? size(total) : "?"));
        } else {
            progress.setIndeterminate(true);
            status.setText(phase == QuestVivecraftService.Phase.VERIFYING
                    ? R.string.qv_download_verifying : R.string.qv_download_installing);
        }
    }

    private void showError(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof CancellationException) {
            status.setText(R.string.qv_download_cancelled);
            return;
        }
        Logging.LOG.log(Level.WARNING, "Quest Vivecraft download from " + source.displayName + " for " + target.instanceId, error);
        int message = R.string.qv_download_network;
        if (error instanceof QuestVivecraftService.ServiceException failure) {
            message = switch (failure.error) {
                case UNSUPPORTED -> R.string.qv_download_unsupported;
                case INVALID_ASSET, INCOMPATIBLE -> R.string.qv_download_incompatible;
                case CONFLICT -> R.string.qv_download_conflict_error;
                case DESTINATION -> R.string.qv_download_destination_error;
                case INTEGRITY -> R.string.qv_download_integrity_error;
                default -> R.string.qv_download_network;
            };
        }
        status.setText(message);
    }

    private static String size(long bytes) {
        return String.format(Locale.getDefault(), "%.1f MiB", bytes / 1048576.0);
    }

    private record Listing(List<QuestVivecraftService.InstalledMod> mods,
                           List<QuestVivecraftService.ReleaseAsset> assets) {}

    private final class ReleaseAdapter extends BaseAdapter {
        @Override public int getCount() {
            return releases.size();
        }
        @Override public Object getItem(int position) {
            return releases.get(position);
        }
        @Override public long getItemId(int position) {
            return releases.get(position).id;
        }
        @Override public View getView(int position, View row, ViewGroup parent) {
            if (row == null) {
                row = LayoutInflater.from(getContext()).inflate(R.layout.item_quest_vivecraft_release, parent, false);
                row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            QuestVivecraftService.ReleaseAsset asset = releases.get(position);
            ((TextView) row.findViewById(R.id.name)).setText(asset.releaseName + " · " + asset.tag);
            boolean present = installed.stream().anyMatch(mod -> !asset.sha256.isEmpty() && asset.sha256.equalsIgnoreCase(mod.sha256));
            String releaseType = getContext().getString(present ? R.string.qv_download_row_installed
                    : asset.prerelease ? R.string.qv_download_prerelease : R.string.qv_download_release);
            ((TextView) row.findViewById(R.id.tag)).setText(asset.source.displayName + " · " + releaseType);
            ((TextView) row.findViewById(R.id.date)).setText(asset.fileName + "  ·  " + size(asset.size)
                    + "  ·  " + asset.publishedAt.replace('T', ' ').replace("Z", " UTC"));
            View button = row.findViewById(R.id.parent);
            button.setEnabled(!busy);
            button.setAlpha(busy ? 0.55f : 1f);
            button.setOnClickListener(v -> confirmInstall(asset));
            return row;
        }
    }
}
