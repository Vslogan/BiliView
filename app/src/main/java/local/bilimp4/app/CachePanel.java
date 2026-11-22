package local.bilimp4.app;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;

final class CachePanel implements AutoCloseable {
    static final int PICK_FOLDER = 1;
    private static final int CACHE_PERMISSION = 2;
    private static final String FOLDER_KEY = "cacheFolder";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_GUIDE = "https://shizuku.rikka.app/zh-hans/guide/setup/";
    private static final String SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/zh-hans/download/";
    private enum Access { MISSING, STOPPED, NEEDS_PERMISSION, DENIED, READY }

    private final MainActivity activity;
    private final Executor worker;
    private final BiConsumer<List<CacheEntry>, ExportOptions> export;
    private final VideoHistory history;
    private final Consumer<Uri> open;
    private final MaterialButton scan;
    private final MaterialButton choose;
    private final MaterialButton batch;
    private final TextView hint;
    private final LinearLayout list;
    private final ExportOptionsControl exportOptions;
    private final MaterialButton audioFormat;
    private final TextView audioHint;
    private LinearLayout audioControls;
    private final List<MaterialButton> exportButtons = new ArrayList<>();
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final ProbeStore probeStore;
    private final MediaProbe prober;
    private final Map<String, TextView> infoViews = new HashMap<>();
    private final Map<String, TextView> detailViews = new HashMap<>();
    private final Map<String, MediaProbe.Info> probed = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> probing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Shizuku.UserServiceArgs service;
    private List<CacheEntry> entries = List.of();
    private Map<String, VideoHistory.CacheResult> results = Map.of();
    private Uri folder;
    private CacheReader reader;
    private Access access;
    private boolean visible;
    private boolean loaded;
    private boolean officialEntries;
    private boolean busy;
    private boolean scanning;
    private boolean binding;
    private boolean closed;

    private final Shizuku.OnRequestPermissionResultListener permission = this::permissionResult;
    private final Shizuku.OnBinderReceivedListener ready = this::refreshAccess;
    private final Shizuku.OnBinderDeadListener stopped = this::refreshAccess;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            reader = new CacheReader(binder);
            if (binding) {
                binding = false;
                scanOfficial(reader);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            reader = null;
            if (binding && !closed) {
                binding = false;
                show(null, "Shizuku 连接已断开，请重新扫描。");
            }
        }
    };

    CachePanel(MainActivity activity, LinearLayout panel, Executor worker, VideoHistory history,
               BiConsumer<List<CacheEntry>, ExportOptions> export, Consumer<Uri> open) {
        this.activity = activity;
        this.worker = worker;
        this.export = export;
        this.history = history;
        this.open = open;
        probeStore = new ProbeStore(activity.getFilesDir());
        prober = new MediaProbe(activity.getCacheDir());
        String saved = activity.getPreferences(0).getString(FOLDER_KEY, null);
        folder = saved == null ? null : Uri.parse(saved);
        service = new Shizuku.UserServiceArgs(new ComponentName(activity, BiliCacheService.class))
                .processNameSuffix("cache").daemon(false).version(2);

        panel.addView(activity.text("转换本地缓存", 28, false));
        panel.addView(activity.text("自动配对音视频，点选一条缓存即可导出视频。", 14, true), activity.spacing(8));
        boolean restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        panel.addView(activity.text(activity.getString(restricted
                ? R.string.cache_access_android_11 : R.string.cache_access), 13, true), activity.spacing(16));

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        scan = activity.button("", com.google.android.material.R.attr.materialButtonStyle);
        scan.setOnClickListener(view -> requestScan());
        actions.addView(scan, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton guide = activity.button(activity.getString(R.string.shizuku_guide_title),
                androidx.appcompat.R.attr.borderlessButtonStyle);
        guide.setOnClickListener(view -> showGuide());
        actions.addView(guide, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(actions, activity.spacing(8));

        choose = activity.button(activity.getString(R.string.cache_choose_shared), com.google.android.material.R.attr.materialButtonOutlinedStyle);
        choose.setOnClickListener(view -> chooseFolder());
        panel.addView(choose, activity.spacing(4));
        panel.addView(activity.text(activity.getString(R.string.cache_shared_example), 13, true), activity.spacing(4));
        hint = activity.text("", 13, true);
        hint.setVisibility(TextView.GONE);
        panel.addView(hint, activity.spacing(12));
        exportOptions = new ExportOptionsControl(activity, panel);
        exportOptions.setVisible(false);
        LinearLayout audioRow = new LinearLayout(activity);
        audioRow.setGravity(Gravity.CENTER_VERTICAL);
        audioFormat = activity.button("", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        audioFormat.setOnClickListener(view -> chooseAudioFormat());
        audioRow.addView(audioFormat, new LinearLayout.LayoutParams(-2, -2));
        audioHint = activity.text("", 12, true);
        LinearLayout.LayoutParams audioHintSize = new LinearLayout.LayoutParams(0, -2, 1);
        audioHintSize.setMarginStart(activity.dp(10));
        audioRow.addView(audioHint, audioHintSize);
        audioControls = audioRow;
        panel.addView(audioRow, activity.spacing(2));
        audioRow.setVisibility(TextView.GONE);
        refreshAudioFormat();
        batch = activity.button("批量转换", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        batch.setVisibility(TextView.GONE);
        panel.addView(batch, activity.spacing(8));
        list = activity.column();
        panel.addView(list, activity.spacing(8));
        refreshAccess();
        Shizuku.addRequestPermissionResultListener(permission);
        Shizuku.addBinderReceivedListenerSticky(ready);
        Shizuku.addBinderDeadListener(stopped);
    }

    void setVisible(boolean visible) {
        this.visible = visible;
        if (visible) {
            refreshAccess();
            refreshResults();
        }
    }

    void setBusy(boolean busy) {
        this.busy = busy;
        updateButtons();
    }

    private void loadOnOpen() {
        if (!visible || loaded || busy || scanning || closed) return;
        if (access == Access.READY) bindAndScan();
        else if (folder != null) scanFolder(folder);
    }

    void refreshAccess() {
        if (closed) return;
        updateAccess();
        loadOnOpen();
    }

    private void updateAccess() {
        Access previous = access;
        access = detectAccess();
        if (previous == Access.READY && access != Access.READY) {
            reader = null;
            if (officialEntries) loaded = false;
        }
        scan.setText(switch (access) {
            case MISSING -> R.string.shizuku_get;
            case STOPPED -> R.string.shizuku_open;
            case NEEDS_PERMISSION -> R.string.shizuku_grant_and_scan;
            case DENIED -> R.string.shizuku_manage_permission;
            case READY -> R.string.cache_scan_official;
        });
        updateButtons();
    }

    private Access detectAccess() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return Access.READY;
                return Shizuku.shouldShowRequestPermissionRationale() ? Access.DENIED : Access.NEEDS_PERMISSION;
            }
        } catch (IllegalStateException ignored) {
            // Shizuku can stop while checking the connection.
        }
        return shizukuInstalled() ? Access.STOPPED : Access.MISSING;
    }

    private boolean shizukuInstalled() {
        try {
            activity.getPackageManager().getApplicationInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private void requestScan() {
        try {
            updateAccess();
            switch (access) {
                case MISSING -> openWebsite(SHIZUKU_DOWNLOAD);
                case STOPPED -> openShizuku();
                case NEEDS_PERMISSION -> Shizuku.requestPermission(CACHE_PERMISSION);
                case DENIED -> {
                    showHint(activity.getString(R.string.shizuku_permission_denied));
                    openShizuku();
                }
                case READY -> bindAndScan();
            }
        } catch (RuntimeException error) {
            refreshAccess();
            showHint("无法连接 Shizuku，请确认其服务正在运行。");
        }
    }

    private void showGuide() {
        boolean installed = shizukuInstalled();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.shizuku_guide_title)
                .setMessage(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? R.string.shizuku_guide_wireless : R.string.shizuku_guide_usb)
                .setPositiveButton(installed ? R.string.shizuku_open : R.string.shizuku_get, (dialog, which) -> {
                    if (installed) openShizuku();
                    else openWebsite(SHIZUKU_DOWNLOAD);
                })
                .setNeutralButton(R.string.shizuku_official_guide, (dialog, which) -> openWebsite(SHIZUKU_GUIDE))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openShizuku() {
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (intent == null) openWebsite(SHIZUKU_GUIDE);
        else startGuideActivity(intent);
    }

    private void openWebsite(String address) {
        startGuideActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
    }

    private void startGuideActivity(Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException error) {
            showHint("无法打开 Shizuku 或浏览器，请手动打开 Shizuku 查看指引。");
        }
    }

    private void permissionResult(int code, int grant) {
        if (code != CACHE_PERMISSION || closed) return;
        updateAccess();
        if (grant == PackageManager.PERMISSION_GRANTED) bindAndScan();
        else showHint(activity.getString(R.string.shizuku_permission_denied));
    }

    private void bindAndScan() {
        if (!beginScan()) return;
        if (reader != null && reader.alive()) {
            scanOfficial(reader);
            return;
        }
        try {
            binding = true;
            Shizuku.bindUserService(service, connection);
            hint.postDelayed(() -> {
                if (binding && !closed) {
                    binding = false;
                    show(null, "连接 Shizuku 超时，请重新扫描。");
                }
            }, 12000);
        } catch (RuntimeException error) {
            binding = false;
            show(null, "无法连接 Shizuku，请确认其服务正在运行。");
        }
    }

    private void scanOfficial(CacheReader source) {
        worker.execute(() -> {
            try {
                JSONArray items = new JSONArray(source.listCaches());
                List<CacheEntry> entries = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String directory = item.getString("directory");
                    entries.add(new CacheEntry("official:" + directory, item.getString("title"), "B 站离线缓存",
                            () -> openFile(source, directory, false, false), () -> openFile(source, directory, true, false),
                            item.optLong("aid"), item.optLong("cid"),
                            commentSource(source, item.optString("xmlFolder"), false),
                            commentSource(source, item.optString("protobufFolder"), true),
                            new CacheEntry.Meta(empty(item.optString("quality")), item.optLong("durationMs"),
                                    item.optInt("width"), item.optInt("height"),
                                    item.optLong("videoBytes"), item.optLong("audioBytes"))));
                }
                activity.runOnUiThread(() -> show(entries, "没有找到完整的 B 站离线缓存。", true));
            } catch (Exception error) {
                activity.runOnUiThread(() -> show(null, "无法读取 B 站缓存，请确认 Shizuku 授权有效。"));
            }
        });
    }

    private static ExportTask.Source commentSource(CacheReader source, String folder, boolean protobuf) {
        return folder.isEmpty() ? null : () -> openFile(source, folder, protobuf, true);
    }

    private static AssetFileDescriptor openFile(CacheReader source, String directory, boolean flag, boolean comments)
            throws IOException {
        try {
            ParcelFileDescriptor fd = comments ? source.openDanmaku(directory, flag) : source.openTrack(directory, flag);
            if (fd == null) throw new IOException("缓存文件已移动或删除。");
            return new AssetFileDescriptor(fd, 0, fd.getStatSize());
        } catch (RemoteException error) {
            throw new IOException("与 Shizuku 的连接已断开，请重新扫描。", error);
        }
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, PICK_FOLDER);
        } catch (ActivityNotFoundException error) {
            showHint("系统没有可用的文件夹选择器。");
        }
    }

    void setFolder(Uri uri) {
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (folder != null && !folder.equals(uri)) {
                try {
                    activity.getContentResolver().releasePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // The previous grant may already have been revoked.
                }
            }
            folder = uri;
            activity.getPreferences(0).edit().putString(FOLDER_KEY, uri.toString()).apply();
        } catch (SecurityException ignored) {
            // Some providers only grant access for the current session.
        }
        scanFolder(uri);
    }

    private void scanFolder(Uri tree) {
        if (!beginScan()) return;
        worker.execute(() -> {
            try {
                List<CacheEntry> entries = SharedCache.scan(activity.getApplicationContext().getContentResolver(), tree);
                activity.runOnUiThread(() -> show(entries, "这个文件夹里没有找到能配对的音视频。", false));
            } catch (Exception error) {
                activity.runOnUiThread(() -> show(null, "无法读取此文件夹，请选择共享子文件夹。"));
            }
        });
    }

    private boolean beginScan() {
        if (scanning || busy || closed) return false;
        scanning = true;
        showHint("正在扫描缓存");
        updateButtons();
        return true;
    }

    private void show(List<CacheEntry> entries, String empty) {
        show(entries, empty, false);
    }

    private void show(List<CacheEntry> entries, String empty, boolean official) {
        if (closed) return;
        scanning = false;
        if (entries != null) {
            loaded = true;
            officialEntries = official;
            this.entries = entries;
            results = history.cacheResults(entries);
            renderEntries();
            boolean any = !entries.isEmpty();
            exportOptions.setVisible(any && audioFormat() == ExportOptions.AudioFormat.NONE);
            audioControls.setVisibility(any ? TextView.VISIBLE : TextView.GONE);
        }
        showHint(entries == null || entries.isEmpty() ? empty
                : activity.getString(R.string.cache_count, entries.size()));
        updateButtons();
    }

    void refreshResults() {
        if (!visible || closed || entries.isEmpty()) return;
        Map<String, VideoHistory.CacheResult> updated = history.cacheResults(entries);
        if (updated.equals(results)) return;
        results = updated;
        renderEntries();
        updateButtons();
    }

    private void renderEntries() {
        list.removeAllViews();
        exportButtons.clear();
        infoViews.clear();
        detailViews.clear();
        List<CacheEntry> unconverted = new ArrayList<>();
        for (CacheEntry entry : entries) {
            VideoHistory.CacheResult result = results.get(entry.key());
            if (result == null) unconverted.add(entry);
            list.addView(row(entry, result), activity.spacing(8));
        }
        batch.setVisibility(unconverted.size() > 1 ? TextView.VISIBLE : TextView.GONE);
        batch.setOnClickListener(view -> BatchPicker.show(activity, "选择要转换的缓存", unconverted,
                CacheEntry::title, items -> export.accept(items, currentOptions())));
    }

    private void showHint(String message) {
        hint.setText(message);
        hint.setVisibility(TextView.VISIBLE);
    }

    private MaterialCardView row(CacheEntry entry, VideoHistory.CacheResult result) {
        MaterialCardView card = new MaterialCardView(activity);
        card.setCardElevation(0);
        card.setRadius(activity.dp(16));
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(activity.dp(14), activity.dp(10), activity.dp(10), activity.dp(10));
        LinearLayout labels = activity.column();
        TextView title = activity.text(entry.title(), 15, false);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        String location = entry.location();
        if (!entry.hasDanmakuSource()) location += " · 无弹幕来源";
        labels.addView(activity.text(location, 12, true), activity.spacing(4));
        LinearLayout infoRow = new LinearLayout(activity);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView info = activity.text("", 12, true);
        info.setMaxLines(2);
        info.setEllipsize(TextUtils.TruncateAt.END);
        infoRow.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView details = activity.text("详细", 12, false);
        details.setTextColor(activity.color(com.google.android.material.R.attr.colorPrimary));
        details.setPadding(activity.dp(8), 0, 0, 0);
        details.setOnClickListener(view -> showDetails(entry));
        details.setVisibility(TextView.GONE);
        infoRow.addView(details, new LinearLayout.LayoutParams(-2, -2));
        labels.addView(infoRow, activity.spacing(4));
        infoViews.put(entry.key(), info);
        detailViews.put(entry.key(), details);
        probe(entry);
        updateInfo(entry);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = activity.column();
        if (result != null) {
            TextView status = activity.text(result.confirmed() ? "已转换" : "已有同名视频", 13, false);
            status.setTextColor(activity.color(com.google.android.material.R.attr.colorPrimary));
            labels.addView(status, activity.spacing(4));
            MaterialButton openButton = activity.button("打开视频", androidx.appcompat.R.attr.borderlessButtonStyle);
            openButton.setContentDescription("打开 " + entry.title());
            openButton.setOnClickListener(view -> {
                open.accept(result.uri());
                refreshResults();
            });
            actions.addView(openButton, new LinearLayout.LayoutParams(-1, -2));
        }
        boolean converted = result != null;
        MaterialButton convert = activity.button(converted ? "重新转换" : "导出",
                androidx.appcompat.R.attr.borderlessButtonStyle);
        convert.setContentDescription((converted ? "重新转换 " : "导出 ") + entry.title());
        convert.setOnClickListener(view -> {
            if (!converted) {
                export.accept(List.of(entry), currentOptions());
                return;
            }
            new MaterialAlertDialogBuilder(activity).setTitle("重新转换？")
                    .setMessage(audioFormat() == ExportOptions.AudioFormat.NONE
                            ? "会另存一份视频。" : "会另存一份音频。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("重新转换", (dialog, which) -> export.accept(List.of(entry), currentOptions()))
                    .show();
        });
        exportButtons.add(convert);
        actions.addView(convert, new LinearLayout.LayoutParams(-1, -2));
        row.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row);
        return card;
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private ExportOptions.AudioFormat audioFormat() {
        int saved = activity.getPreferences(0).getInt("cacheAudioFormat", 0);
        ExportOptions.AudioFormat[] formats = ExportOptions.AudioFormat.values();
        return saved >= 0 && saved < formats.length ? formats[saved] : ExportOptions.AudioFormat.NONE;
    }

    private void chooseAudioFormat() {
        ExportOptions.AudioFormat[] formats = ExportOptions.AudioFormat.values();
        String[] labels = new String[formats.length];
        for (int i = 0; i < formats.length; i++) labels[i] = formats[i].label();
        new MaterialAlertDialogBuilder(activity).setTitle("音频导出")
                .setSingleChoiceItems(labels, audioFormat().ordinal(), (dialog, which) -> {
                    activity.getPreferences(0).edit().putInt("cacheAudioFormat", which).apply();
                    dialog.dismiss();
                    refreshAudioFormat();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshAudioFormat() {
        ExportOptions.AudioFormat format = audioFormat();
        audioFormat.setText("音频导出 · " + (format == ExportOptions.AudioFormat.NONE ? "关闭" : format.name()));
        boolean audioOnly = format != ExportOptions.AudioFormat.NONE;
        audioHint.setText(audioOnly ? "仅导出音轨，忽略视频与弹幕。" : "");
        audioHint.setVisibility(audioOnly ? TextView.VISIBLE : TextView.GONE);
        exportOptions.setVisible(!entries.isEmpty() && !audioOnly);
        if (batch != null) updateButtons();
    }

    private ExportOptions currentOptions() {
        return exportOptions.current().withAudio(audioFormat());
    }

    // ---------- 媒体信息分析 ----------

    private void probe(CacheEntry entry) {
        String key = ProbeStore.key(entry);
        if (probed.containsKey(key)) return;
        MediaProbe.Info cached = probeStore.get(key);
        if (cached != null) {
            probed.put(key, cached);
            return;
        }
        if (entry.video() == null && entry.audio() == null) return;
        if (!probing.add(key)) return;
        probeExecutor.execute(() -> {
            try {
                MediaProbe.Info info = prober.probe(entry);
                if (info.video() == null && info.audio() == null) return;
                probed.put(key, info);
                probeStore.put(key, info);
                activity.runOnUiThread(() -> {
                    if (!closed) updateInfo(entry);
                });
            } catch (IOException | RuntimeException ignored) {
                // 分析失败时保留缓存元数据里的基本信息
            } finally {
                probing.remove(key);
            }
        });
    }

    private void updateInfo(CacheEntry entry) {
        TextView view = infoViews.get(entry.key());
        if (view == null || closed) return;
        String key = ProbeStore.key(entry);
        MediaProbe.Info info = probed.get(key);
        view.setText(info == null ? quickInfo(entry, probing.contains(key)) : compactInfo(info));
        TextView detail = detailViews.get(entry.key());
        if (detail != null) detail.setVisibility(info == null ? TextView.GONE : TextView.VISIBLE);
    }

    /** Quick facts from entry.json before the real analysis finishes. */
    private static String quickInfo(CacheEntry entry, boolean analyzing) {
        CacheEntry.Meta meta = entry.meta();
        StringBuilder text = new StringBuilder();
        if (meta != null) {
            if (meta.quality() != null) text.append(meta.quality());
            if (meta.width() > 0) append(text, meta.width() + "×" + meta.height());
            if (meta.durationMs() > 0) append(text, "约 " + durationText(meta.durationMs()));
            long size = meta.videoBytes() + meta.audioBytes();
            if (size > 0) append(text, "约 " + bytesText(size));
        }
        if (analyzing) append(text, "分析中…");
        return text.toString();
    }

    /** One compact line with the measured parameters. */
    private static String compactInfo(MediaProbe.Info info) {
        StringBuilder text = new StringBuilder();
        MediaProbe.Track video = info.video();
        if (video != null) {
            if (video.width() > 0) append(text, video.width() + "×" + video.height());
            if (video.fps() > 0) {
                append(text, String.format(Locale.ROOT, "%.1f fps", video.fps()));
            }
            if (video.bitrateBps() > 0) append(text, bitrateText(video.bitrateBps()));
            append(text, MediaProbe.codecName(video.codec()));
        }
        MediaProbe.Track audio = info.audio();
        if (audio != null) {
            StringBuilder part = new StringBuilder(MediaProbe.codecName(audio.codec()));
            if (audio.sampleRate() > 0) {
                part.append(' ').append(String.format(Locale.ROOT, "%.1f kHz", audio.sampleRate() / 1000f));
            }
            if (audio.channels() > 0) part.append(' ').append(channelsText(audio.channels()));
            if (audio.bitrateBps() > 0) part.append(" · ").append(bitrateText(audio.bitrateBps()));
            append(text, part.toString());
        }
        long durationMs = video != null ? video.durationMs() : audio != null ? audio.durationMs() : 0;
        if (durationMs > 0) append(text, durationText(durationMs));
        return text.toString();
    }

    private void showDetails(CacheEntry entry) {
        MediaProbe.Info info = probed.get(ProbeStore.key(entry));
        if (info == null) return;
        StringBuilder text = new StringBuilder();
        MediaProbe.Track video = info.video();
        if (video != null) {
            text.append("视频\n编码：").append(MediaProbe.codecName(video.codec()));
            if (video.width() > 0) text.append("\n分辨率：").append(video.width()).append('×').append(video.height());
            if (video.fps() > 0) {
                text.append("\n平均帧率：").append(String.format(Locale.ROOT, "%.2f fps", video.fps()));
            }
            if (video.bitrateBps() > 0) text.append("\n码率：").append(bitrateText(video.bitrateBps()));
            if (video.samples() > 0) text.append("\n总帧数：").append(video.samples());
            if (video.durationMs() > 0) text.append("\n时长：").append(durationText(video.durationMs()));
            if (video.bytes() > 0) text.append("\n媒体数据：").append(bytesText(video.bytes()));
        }
        MediaProbe.Track audio = info.audio();
        if (audio != null) {
            if (text.length() > 0) text.append("\n\n");
            text.append("音频\n编码：").append(MediaProbe.codecName(audio.codec()));
            if (audio.sampleRate() > 0) text.append("\n采样率：").append(audio.sampleRate()).append(" Hz");
            if (audio.channels() > 0) text.append("\n声道：").append(channelsText(audio.channels()));
            if (audio.bitrateBps() > 0) text.append("\n码率：").append(bitrateText(audio.bitrateBps()));
            if (audio.durationMs() > 0) text.append("\n时长：").append(durationText(audio.durationMs()));
            if (audio.bytes() > 0) text.append("\n媒体数据：").append(bytesText(audio.bytes()));
        }
        if (info.md5() != null) text.append("\n\nMD5（视频文件）：").append(info.md5());
        CacheEntry.Meta meta = entry.meta();
        if (meta != null && meta.quality() != null) {
            text.append("\n画质标注：").append(meta.quality()).append("（来自缓存信息，参数以实测为准）");
        }
        new MaterialAlertDialogBuilder(activity).setTitle(entry.title())
                .setMessage(text.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void append(StringBuilder text, String part) {
        if (part == null || part.isEmpty()) return;
        if (text.length() > 0) text.append(" · ");
        text.append(part);
    }

    private static String bitrateText(long bps) {
        if (bps >= 1_000_000) return String.format(Locale.ROOT, "%.2f Mbps", bps / 1_000_000f);
        if (bps >= 1_000) return String.format(Locale.ROOT, "%.0f kbps", bps / 1_000f);
        return bps + " bps";
    }

    private static String channelsText(int channels) {
        return switch (channels) {
            case 1 -> "单声道";
            case 2 -> "双声道";
            default -> channels + " 声道";
        };
    }

    private static String durationText(long durationMs) {
        long seconds = Math.max(0, durationMs / 1000);
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        if (hours > 0) return hours + " 小时 " + minutes + " 分 " + rest + " 秒";
        if (minutes > 0) return minutes + " 分 " + rest + " 秒";
        return rest + " 秒";
    }

    private static String bytesText(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }

    private void updateButtons() {
        boolean enabled = !busy && !scanning;
        scan.setEnabled(enabled);
        choose.setEnabled(enabled);
        batch.setEnabled(enabled && (!officialEntries || access == Access.READY));
        audioFormat.setEnabled(enabled);
        exportOptions.setBusy(!enabled || audioFormat() != ExportOptions.AudioFormat.NONE);
        for (MaterialButton button : exportButtons) {
            button.setEnabled(enabled && (!officialEntries || access == Access.READY));
        }
    }

    @Override
    public void close() {
        closed = true;
        probeExecutor.shutdownNow();
        Shizuku.removeRequestPermissionResultListener(permission);
        Shizuku.removeBinderReceivedListener(ready);
        Shizuku.removeBinderDeadListener(stopped);
        if (reader != null || binding) ExportQueue.get(activity).whenIdle(() -> {
            try {
                Shizuku.unbindUserService(service, connection, false);
            } catch (RuntimeException ignored) {
                // Shizuku may already have stopped.
            }
        });
    }
}
