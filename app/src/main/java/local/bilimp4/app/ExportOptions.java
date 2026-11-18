package local.bilimp4.app;

/**
 * How a video export should be packaged: the container format plus what happens to danmaku.
 * The style options are only present when danmaku are actually processed (any mode but NONE).
 */
record ExportOptions(boolean mkv, ExportOptions.DanmakuMode danmaku, DanmakuStyle.Options style) {
    enum DanmakuMode { NONE, BURN, ASS_FILE, ASS_EMBED }

    static ExportOptions none() {
        return new ExportOptions(false, DanmakuMode.NONE, null);
    }

    /** Matroska is the only container that can carry the ASS subtitle track. */
    ExportOptions normalized() {
        return danmaku == DanmakuMode.ASS_EMBED && !mkv ? new ExportOptions(true, danmaku, style) : this;
    }
}
