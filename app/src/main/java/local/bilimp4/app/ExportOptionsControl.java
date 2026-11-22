package local.bilimp4.app;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Shared export-options control: the container format (MP4 or MKV) and how danmaku should be
 * handled (off, burned into the picture, exported as a standalone ASS file, or embedded as an
 * ASS subtitle track). Both choices are persisted and shared by link downloads, cache
 * conversion and the favorites sheet. Embedding subtitles requires MKV, so the two pickers
 * keep each other consistent.
 */
final class ExportOptionsControl {
    private static final String KEY_MKV = "exportMkv";
    private static final String KEY_MODE = "danmakuMode";

    private final MainActivity activity;
    private final LinearLayout controls;
    private final MaterialButton format;
    private final MaterialButton danmaku;
    private final MaterialButton style;
    private final TextView hint;
    private Runnable onChange;

    ExportOptionsControl(MainActivity activity, LinearLayout parent) {
        this.activity = activity;
        controls = activity.column();
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        format = activity.button("", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        format.setOnClickListener(view -> chooseFormat());
        row.addView(format, new LinearLayout.LayoutParams(0, -2, 1));
        danmaku = activity.button("", com.google.android.material.R.attr.materialButtonOutlinedStyle);
        danmaku.setOnClickListener(view -> chooseDanmaku());
        LinearLayout.LayoutParams danmakuSize = new LinearLayout.LayoutParams(0, -2, 1);
        danmakuSize.setMarginStart(activity.dp(10));
        row.addView(danmaku, danmakuSize);
        controls.addView(row);

        LinearLayout second = new LinearLayout(activity);
        second.setGravity(Gravity.CENTER_VERTICAL);
        hint = activity.text("", 12, true);
        second.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        style = activity.button("弹幕设置", androidx.appcompat.R.attr.borderlessButtonStyle);
        style.setOnClickListener(view -> DanmakuControls.showSettings(activity));
        second.addView(style, new LinearLayout.LayoutParams(-2, -2));
        controls.addView(second, activity.spacing(2));
        parent.addView(controls, activity.spacing(4));
        refresh();
        setBusy(false);
    }

    void onChange(Runnable listener) {
        onChange = listener;
    }

    void setVisible(boolean visible) {
        controls.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void setBusy(boolean busy) {
        format.setEnabled(!busy);
        danmaku.setEnabled(!busy);
        style.setEnabled(!busy);
    }

    /** The effective container choice, honoring that embedded subtitles require MKV. */
    boolean mkvEffective() {
        return preferences().getBoolean(KEY_MKV, false) || mode() == ExportOptions.DanmakuMode.ASS_EMBED;
    }

    /** The current choices, with the style options attached when danmaku are processed. */
    ExportOptions current() {
        ExportOptions.DanmakuMode mode = mode();
        boolean mkv = preferences().getBoolean(KEY_MKV, false);
        DanmakuStyle.Options styleOptions = mode == ExportOptions.DanmakuMode.NONE ? null
                : DanmakuControls.loadOptions(activity);
        return new ExportOptions(mkv, mode, styleOptions, ExportOptions.AudioFormat.NONE).normalized();
    }

    /** Rebuilds the options recorded for an earlier task, with today's style settings. */
    static ExportOptions restore(MainActivity activity, VideoHistory.Download download) {
        ExportOptions.DanmakuMode mode = parseMode(download.danmakuMode());
        DanmakuStyle.Options style = mode == ExportOptions.DanmakuMode.NONE ? null
                : DanmakuControls.loadOptions(activity);
        return new ExportOptions(download.mkv(), mode, style, ExportOptions.AudioFormat.NONE).normalized();
    }

    private SharedPreferences preferences() {
        return activity.getPreferences(0);
    }

    private ExportOptions.DanmakuMode mode() {
        return parseMode(preferences().getInt(KEY_MODE, 0));
    }

    private static ExportOptions.DanmakuMode parseMode(int value) {
        ExportOptions.DanmakuMode[] modes = ExportOptions.DanmakuMode.values();
        return value >= 0 && value < modes.length ? modes[value] : ExportOptions.DanmakuMode.NONE;
    }

    private static ExportOptions.DanmakuMode parseMode(String name) {
        if (name != null) {
            try {
                return ExportOptions.DanmakuMode.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Unknown modes fall through to no danmaku.
            }
        }
        return ExportOptions.DanmakuMode.NONE;
    }

    private void chooseFormat() {
        String[] items = {"MP4", "MKV（可内嵌弹幕字幕轨）"};
        new MaterialAlertDialogBuilder(activity).setTitle("导出格式")
                .setSingleChoiceItems(items, mkvEffective() ? 1 : 0, (dialog, which) -> {
                    boolean mkv = which == 1;
                    SharedPreferences.Editor editor = preferences().edit().putBoolean(KEY_MKV, mkv);
                    if (!mkv && mode() == ExportOptions.DanmakuMode.ASS_EMBED) {
                        editor.putInt(KEY_MODE, ExportOptions.DanmakuMode.ASS_FILE.ordinal());
                        Toast.makeText(activity, "MP4 不支持内嵌字幕轨，已改为导出 ASS 字幕文件", Toast.LENGTH_LONG).show();
                    }
                    editor.apply();
                    refresh();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseDanmaku() {
        String[] items = {"不处理弹幕", "烧录进画面（重新编码）", "导出 ASS 字幕文件", "内嵌 ASS 字幕轨（MKV）"};
        new MaterialAlertDialogBuilder(activity).setTitle("弹幕处理")
                .setSingleChoiceItems(items, mode().ordinal(), (dialog, which) -> {
                    SharedPreferences.Editor editor = preferences().edit().putInt(KEY_MODE, which);
                    if (which == ExportOptions.DanmakuMode.ASS_EMBED.ordinal() && !mkvEffective()) {
                        editor.putBoolean(KEY_MKV, true);
                        Toast.makeText(activity, "已切换为 MKV 封装以内嵌字幕", Toast.LENGTH_LONG).show();
                    }
                    editor.apply();
                    refresh();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refresh() {
        format.setText("格式 · " + (mkvEffective() ? "MKV" : "MP4"));
        ExportOptions.DanmakuMode mode = mode();
        String label = switch (mode) {
            case NONE -> "弹幕 · 关闭";
            case BURN -> "弹幕 · 烧录";
            case ASS_FILE -> "弹幕 · ASS 文件";
            case ASS_EMBED -> "弹幕 · 内嵌字幕";
        };
        danmaku.setText(label);
        String text = switch (mode) {
            case NONE -> "";
            case BURN -> "烧录会重新编码视频，弹幕固定在画面里。";
            case ASS_FILE -> "同时导出同名 .ass 弹幕字幕文件，需播放器加载后显示。";
            case ASS_EMBED -> "弹幕作为字幕轨封装进 MKV，可在播放器中开关。";
        };
        hint.setText(text);
        hint.setVisibility(mode == ExportOptions.DanmakuMode.NONE ? View.GONE : View.VISIBLE);
        style.setVisibility(mode == ExportOptions.DanmakuMode.NONE ? View.GONE : View.VISIBLE);
        if (onChange != null) onChange.run();
    }
}
