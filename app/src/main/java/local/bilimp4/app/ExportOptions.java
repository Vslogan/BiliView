package local.bilimp4.app;

/**
 * How a video export should be packaged: the container format plus what happens to danmaku.
 * The style options are only present when danmaku are actually processed (any mode but NONE).
 * When an audio format is set, only the audio track is exported and the video and danmaku
 * settings are ignored.
 */
record ExportOptions(boolean mkv, ExportOptions.DanmakuMode danmaku, DanmakuStyle.Options style,
                     ExportOptions.AudioFormat audio, int mp3Bitrate) {
    enum DanmakuMode { NONE, BURN, ASS_FILE, ASS_EMBED }

    /** Audio-only export targets: plain remux, lossless PCM, or a 320 kbps MP3 encode. */
    enum AudioFormat {
        NONE, M4A, MP3, WAV;

        String extension() {
            return switch (this) {
                case M4A -> ".m4a";
                case MP3 -> ".mp3";
                case WAV -> ".wav";
                default -> "";
            };
        }

        String mime() {
            return switch (this) {
                case M4A -> "audio/mp4";
                case MP3 -> "audio/mpeg";
                case WAV -> "audio/x-wav";
                default -> "";
            };
        }

        String label() {
            return switch (this) {
                case M4A -> "M4A（直接复制音轨）";
                case MP3 -> "MP3（可选码率）";
                case WAV -> "WAV（无损 PCM）";
                default -> "关闭";
            };
        }
    }

    static ExportOptions none() {
        return new ExportOptions(false, DanmakuMode.NONE, null, AudioFormat.NONE, 0);
    }

    /** Matroska is the only container that can carry the ASS subtitle track. */
    ExportOptions normalized() {
        return danmaku == DanmakuMode.ASS_EMBED && !mkv ? new ExportOptions(true, danmaku, style, audio, mp3Bitrate) : this;
    }

    /** The same video options, but exporting only the audio track in the given format. */
    ExportOptions withAudio(AudioFormat format) {
        return new ExportOptions(mkv, danmaku, style, format == null ? AudioFormat.NONE : format, mp3Bitrate);
    }

    /** MP3 target: 0 = auto VBR (V0, quality first); 1 = VBR V2 (smaller); 32…320 = CBR kbps. */
    ExportOptions withBitrate(int bitrate) {
        return new ExportOptions(mkv, danmaku, style, audio, bitrate);
    }
}
