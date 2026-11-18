package local.bilimp4.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.MuxerException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
final class ExportTask {
    static final String FOLDER = "BiliMP4";
    static final String PATH = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
    private static final int BUFFER_SIZE = 128 * 1024;
    private final Context context;
    private final String folder;
    private final Progress listener;
    private volatile boolean cancelled;
    private long lastUpdate;
    private String lastStage;

    record Result(Uri uri, String name, boolean burned, String note, String folder, boolean audio) {
        boolean mkv() {
            return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mkv");
        }
    }

    interface Progress {
        void update(String stage, int percent);
    }

    interface Source {
        AssetFileDescriptor open() throws IOException;
    }

    private interface VideoWriter {
        void write(FileOutputStream output) throws IOException;
    }

    static final class Cancelled extends IOException {}

    ExportTask(Context context, String folder, Progress listener) {
        this.context = context.getApplicationContext();
        this.folder = folder;
        this.listener = listener;
    }

    void cancel() {
        cancelled = true;
    }

    void check() throws Cancelled {
        if (cancelled) throw new Cancelled();
    }

    void progress(String stage, int percent) {
        long now = SystemClock.elapsedRealtime();
        if (!stage.equals(lastStage) || now - lastUpdate >= 150 || percent == 100) {
            listener.update(stage, percent);
            lastStage = stage;
            lastUpdate = now;
        }
    }

    BiliClient.Streams prepareLink(String link, String cookie) throws Exception {
        progress("正在解析视频链接", -1);
        return BiliClient.resolve(link, cookie, this);
    }

    BiliClient.Inspection inspectLink(String link, String cookie) throws Exception {
        progress("正在解析视频或合集", -1);
        return BiliClient.inspect(link, cookie, this);
    }

    Result fromLink(BiliClient.Streams streams, BiliClient.Quality quality, ExportOptions options,
                    String cookie, boolean audioOnly) throws IOException {
        if (audioOnly) return fromLinkAudio(streams, quality);
        if (options == null) options = ExportOptions.none();
        File video = File.createTempFile("bili-video-", ".m4s", context.getCacheDir());
        File audio = null;
        try {
            progress("正在下载视频", -1);
            BiliClient.download(quality.urls(), streams.referer(), video, "正在下载视频", this);
            if (!streams.audio().isEmpty()) {
                audio = File.createTempFile("bili-audio-", ".m4s", context.getCacheDir());
                progress("正在下载音频", -1);
                BiliClient.download(streams.audio(), streams.referer(), audio, "正在下载音频", this);
            }
            File sound = audio == null ? video : audio;
            if (options.danmaku() == ExportOptions.DanmakuMode.NONE) {
                return export(video, sound, streams.title(), options.mkv());
            }

            progress("正在下载弹幕", -1);
            List<Danmaku.Comment> comments;
            try {
                comments = Danmaku.fetch(streams.cid(), streams.referer(), this);
            } catch (Cancelled cancelled) {
                throw cancelled;
            } catch (IOException error) {
                return export(video, sound, streams.title(), options.mkv(),
                        "弹幕未" + danmakuVerb(options.danmaku()) + " · " + error.getMessage());
            }
            DanmakuStyle.Resolved style = Danmaku.resolveStyle(streams.aid(), streams.cid(), streams.referer(),
                    cookie, options.style(), this);
            return switch (options.danmaku()) {
                case BURN -> burn(video, sound, streams.title(), comments, style, options.mkv());
                case ASS_FILE -> exportWithSubtitleFile(video, sound, streams.title(), comments, style,
                        options.mkv());
                case ASS_EMBED -> exportEmbedded(video, sound, streams.title(), comments, style);
                default -> export(video, sound, streams.title(), options.mkv());
            };
        } finally {
            video.delete();
            if (audio != null) audio.delete();
        }
    }

    private Result fromLinkAudio(BiliClient.Streams streams, BiliClient.Quality quality) throws IOException {
        File source = File.createTempFile("bili-audio-", ".m4s", context.getCacheDir());
        try {
            progress("正在下载音频", -1);
            List<String> urls = streams.audio().isEmpty() ? quality.urls() : streams.audio();
            BiliClient.download(urls, streams.referer(), source, "正在下载音频", this);
            return exportAudio(source, streams.title());
        } finally {
            source.delete();
        }
    }

    private Result exportAudio(File source, String title) throws IOException {
        progress("正在合成音频", -1);
        return publish(title + " - 音频", false, null, true, false, output -> muxAudio(source, output));
    }

    private void muxAudio(File source, FileOutputStream output) throws IOException {
        try (Mp4Track audio = Mp4Track.open(source, C.TRACK_TYPE_AUDIO)) {
            Mp4Track.Sample sample = audio.next();
            if (sample == null || audio.format() == null) throw new IOException("所选文件没有可导出的音轨。");
            try (Mp4Muxer muxer = new Mp4Muxer.Builder(output).setSampleBatchingEnabled(false).build()) {
                int track = muxer.addTrack(audio.format());
                long end = 0;
                while (sample != null) {
                    check();
                    writeSample(muxer, track, sample);
                    end = Math.max(end, sample.timeUs());
                    sample = audio.next();
                    progress("正在合成音频", percent(end, audio.durationUs(), 99));
                }
            }
        } catch (MuxerException | IllegalArgumentException | IllegalStateException error) {
            throw new IOException("无法把音轨封装为 M4A。请检查所选文件是否完整。", error);
        }
        progress("正在合成音频", 100);
    }

    private Result burn(File video, File audio, String title, List<Danmaku.Comment> comments,
                        DanmakuStyle.Resolved style, boolean mkv) throws IOException {
        check();
        File source = File.createTempFile("bili-source-", ".mp4", context.getCacheDir());
        File destination = null;
        File repackaged = null;
        try {
            progress("正在合成 MP4", -1);
            try (FileOutputStream output = new FileOutputStream(source)) {
                mux(video, audio, output);
            }
            video.delete();
            if (!audio.equals(video)) audio.delete();
            try {
                destination = File.createTempFile("bili-burn-", ".mp4", context.getCacheDir());
                DanmakuBurner.export(context, source, destination, comments, style.style(), this);
            } catch (Cancelled cancelled) {
                throw cancelled;
            } catch (IOException error) {
                return publishSource(source, title, "弹幕未嵌入，已保存原视频 · " + error.getMessage(), mkv);
            }
            String note = style.synced() ? "已应用 B 站账号弹幕设置" : "使用本机弹幕样式";
            if (!mkv) return publishFile(destination, title + " - 弹幕", true, note, false);
            progress("正在合成 MKV", -1);
            repackaged = File.createTempFile("bili-mkv-", ".mkv", context.getCacheDir());
            muxMkv(destination, destination, null, repackaged);
            return publishFile(repackaged, title + " - 弹幕", true, note, true);
        } finally {
            source.delete();
            if (destination != null) destination.delete();
            if (repackaged != null) repackaged.delete();
        }
    }

    private Result publishSource(File source, String title, String note, boolean mkv) throws IOException {
        if (!mkv) return publishFile(source, title, false, note, false);
        progress("正在合成 MKV", -1);
        File repackaged = File.createTempFile("bili-mkv-", ".mkv", context.getCacheDir());
        try {
            muxMkv(source, source, null, repackaged);
            return publishFile(repackaged, title, false, note, true);
        } finally {
            repackaged.delete();
        }
    }

    private Result publishFile(File file, String title, boolean burned, String note, boolean mkv)
            throws IOException {
        String stage = mkv ? "正在保存 MKV" : "正在保存 MP4";
        progress(stage, -1);
        return publish(title, burned, note, false, mkv, output -> {
            try (InputStream input = new FileInputStream(file)) {
                copy(input, output, 0, file.length(), stage, this);
            }
        });
    }

    Result fromFiles(CacheEntry entry, ExportOptions options, String cookie) throws IOException {
        File video = stageFile(entry.video(), "正在读取视频文件");
        File audio = null;
        try {
            audio = stageFile(entry.audio(), "正在读取音频文件");
            if (options == null || options.danmaku() == ExportOptions.DanmakuMode.NONE) {
                return export(video, audio, entry.title(), options != null && options.mkv());
            }

            progress("正在读取缓存弹幕", -1);
            String referer = entry.aid() > 0 ? BiliClient.SITE + "/video/av" + entry.aid() + "/" : BiliClient.SITE;
            List<Danmaku.Comment> comments;
            try {
                comments = Danmaku.readCache(entry, referer, this);
            } catch (Cancelled cancelled) {
                throw cancelled;
            } catch (IOException error) {
                return export(video, audio, entry.title(), options.mkv(),
                        "弹幕未" + danmakuVerb(options.danmaku()) + " · " + error.getMessage());
            }
            DanmakuStyle.Resolved style = Danmaku.resolveStyle(entry.aid(), entry.cid(), referer, cookie,
                    options.style(), this);
            return switch (options.danmaku()) {
                case BURN -> burn(video, audio, entry.title(), comments, style, options.mkv());
                case ASS_FILE -> exportWithSubtitleFile(video, audio, entry.title(), comments, style,
                        options.mkv());
                case ASS_EMBED -> exportEmbedded(video, audio, entry.title(), comments, style);
                default -> export(video, audio, entry.title(), options.mkv());
            };
        } finally {
            video.delete();
            if (audio != null) audio.delete();
        }
    }

    private File stageFile(Source source, String stage) throws IOException {
        check();
        progress(stage, -1);
        File file = File.createTempFile("bili-import-", ".m4s", context.getCacheDir());
        boolean ready = false;
        try {
            try (AssetFileDescriptor descriptor = source.open()) {
                if (descriptor == null) throw new IOException("无法打开所选文件。");
                try (InputStream input = descriptor.createInputStream();
                     FileOutputStream output = new FileOutputStream(file)) {
                    byte[] prefix = new byte[17];
                    int count = 0;
                    while (count < prefix.length) {
                        int read = input.read(prefix, count, prefix.length - count);
                        if (read == -1) break;
                        count += read;
                    }
                    int skip = cachePrefix(prefix, count);
                    output.write(prefix, skip, count - skip);
                    long length = descriptor.getLength();
                    copy(input, output, count - skip, length < 0 ? -1 : length - skip, stage, this);
                    if (length >= 0 && file.length() != length - skip) {
                        throw new IOException("所选文件读取不完整。");
                    }
                }
            }
            ready = true;
            return file;
        } finally {
            if (!ready) file.delete();
        }
    }

    private static int cachePrefix(byte[] prefix, int count) {
        // Some Bilibili cache files insert nine ASCII zeroes before the first MP4 box.
        if (count < 17) return 0;
        for (int i = 0; i < 9; i++) {
            if (prefix[i] != '0') return 0;
        }
        String box = new String(prefix, 13, 4, StandardCharsets.US_ASCII);
        return box.equals("ftyp") || box.equals("styp") ? 9 : 0;
    }

    static void copy(InputStream input, OutputStream output, long written, long total,
                     String stage, ExportTask task) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            task.check();
            int count = input.read(buffer);
            if (count < 0) break;
            output.write(buffer, 0, count);
            written += count;
            task.progress(stage, percent(written, total, 99));
        }
        task.check();
        task.progress(stage, 100);
    }

    private static int percent(long position, long length, int max) {
        return length <= 0 ? -1 : (int) Math.min(max, 100.0 * position / length);
    }

    private Result export(File video, File audio, String title, boolean mkv) throws IOException {
        return export(video, audio, title, mkv, null);
    }

    private Result export(File video, File audio, String title, boolean mkv, String note) throws IOException {
        if (!mkv) {
            progress("正在合成 MP4", -1);
            return publish(title, false, note, false, false, output -> mux(video, audio, output));
        }
        progress("正在合成 MKV", -1);
        File temp = File.createTempFile("bili-mkv-", ".mkv", context.getCacheDir());
        try {
            muxMkv(video, audio, null, temp);
            return publishFile(temp, title, false, note, true);
        } finally {
            temp.delete();
        }
    }

    private Result exportWithSubtitleFile(File video, File audio, String title,
                                          List<Danmaku.Comment> comments, DanmakuStyle.Resolved style, boolean mkv)
            throws IOException {
        int[] size = videoSize(video);
        DanmakuAss.Script script = DanmakuAss.create(comments, style.style(), size[0], size[1]);
        Result result = export(video, audio, title, mkv, null);
        String note;
        try {
            String name = publishSubtitle(stripExtension(result.name()), DanmakuAss.serialize(script));
            note = (style.synced() ? "已应用 B 站账号弹幕设置 · " : "") + "已导出 ASS 弹幕字幕：" + name;
        } catch (Cancelled cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException error) {
            note = "ASS 弹幕字幕导出失败 · " + error.getMessage();
        }
        return new Result(result.uri(), result.name(), false, note, result.folder(), false);
    }

    private Result exportEmbedded(File video, File audio, String title, List<Danmaku.Comment> comments,
                                  DanmakuStyle.Resolved style) throws IOException {
        int[] size = videoSize(video);
        DanmakuAss.Script script = DanmakuAss.create(comments, style.style(), size[0], size[1]);
        progress("正在合成 MKV", -1);
        File temp = File.createTempFile("bili-mkv-", ".mkv", context.getCacheDir());
        try {
            muxMkv(video, audio, script, temp);
            String note = (style.synced() ? "已应用 B 站账号弹幕设置 · " : "")
                    + "已内嵌 ASS 弹幕字幕，可在播放器中开关";
            return publishFile(temp, title, false, note, true);
        } finally {
            temp.delete();
        }
    }

    private int[] videoSize(File file) throws IOException {
        try (Mp4Track track = Mp4Track.open(file, C.TRACK_TYPE_VIDEO)) {
            if (track.next() == null || track.format() == null) {
                throw new IOException("所选缓存缺少可读取的视频轨。");
            }
            return new int[]{track.format().width, track.format().height};
        }
    }

    private static String danmakuVerb(ExportOptions.DanmakuMode mode) {
        return switch (mode) {
            case BURN -> "烧录";
            case ASS_FILE -> "导出";
            case ASS_EMBED -> "内嵌";
            default -> "处理";
        };
    }

    private Result publish(String title, boolean burned, String note, boolean audio, boolean mkv,
                           VideoWriter writer) throws IOException {
        check();
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        String name = fileName(title, audio, mkv);
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, audio ? "audio/mp4" : mkv ? "video/x-matroska" : "video/mp4");
        values.put(MediaStore.Downloads.RELATIVE_PATH, folder == null ? PATH : PATH + "/" + folder);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("无法创建下载文件。");
        boolean published = false;
        try {
            ParcelFileDescriptor output = resolver.openFileDescriptor(uri, "rw");
            if (output == null) throw new IOException("无法写入下载文件。");
            try (FileOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                writer.write(stream);
            }
            check();
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) throw new IOException("无法发布下载文件。");
            published = true;
            return new Result(uri, displayName(resolver, uri, name), burned, note, folder, audio);
        } finally {
            if (!published) resolver.delete(uri, null, null);
        }
    }

    private String publishSubtitle(String base, String content) throws IOException {
        check();
        progress("正在导出 ASS 弹幕字幕", -1);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, base + ".ass");
        values.put(MediaStore.Downloads.MIME_TYPE, "text/x-ssa");
        values.put(MediaStore.Downloads.RELATIVE_PATH, folder == null ? PATH : PATH + "/" + folder);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("无法创建字幕文件。");
        boolean published = false;
        try {
            ParcelFileDescriptor output = resolver.openFileDescriptor(uri, "rw");
            if (output == null) throw new IOException("无法写入字幕文件。");
            try (FileOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                stream.write(content.getBytes(StandardCharsets.UTF_8));
            }
            check();
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) throw new IOException("无法发布字幕文件。");
            published = true;
            return displayName(resolver, uri, base + ".ass");
        } finally {
            if (!published) resolver.delete(uri, null, null);
        }
    }

    private void mux(File videoFile, File audioFile, FileOutputStream output) throws IOException {
        try (Mp4Track video = Mp4Track.open(videoFile, C.TRACK_TYPE_VIDEO);
             Mp4Track audio = Mp4Track.open(audioFile, C.TRACK_TYPE_AUDIO)) {
            Mp4Track.Sample videoSample = video.next();
            Mp4Track.Sample audioSample = audio.next();
            if (videoSample == null || audioSample == null || video.format() == null || audio.format() == null) {
                throw new IOException("所选缓存缺少可读取的音频或视频轨。");
            }
            try (Mp4Muxer muxer = new Mp4Muxer.Builder(output).setSampleBatchingEnabled(false).build()) {
                int videoTrack = muxer.addTrack(video.format());
                int audioTrack = muxer.addTrack(audio.format());
                if (video.format().rotationDegrees != 0) {
                    muxer.addMetadataEntry(new Mp4OrientationData(video.format().rotationDegrees));
                }
                writeTracks(muxer, video, videoTrack, videoSample, audio, audioTrack, audioSample);
            }
        } catch (MuxerException | IllegalArgumentException | IllegalStateException error) {
            throw new IOException("无法将这两条音视频轨封装为 MP4。请检查缓存是否完整且可播放。", error);
        }
    }

    private void writeTracks(Mp4Muxer muxer, Mp4Track video, int videoTrack, Mp4Track.Sample videoSample,
                             Mp4Track audio, int audioTrack, Mp4Track.Sample audioSample)
            throws IOException, MuxerException {
        long videoEnd = 0;
        long audioEnd = 0;
        while (videoSample != null || audioSample != null) {
            check();
            if (videoSample != null && (audioSample == null || videoSample.timeUs() <= audioSample.timeUs())) {
                writeSample(muxer, videoTrack, videoSample);
                videoEnd = Math.max(videoEnd, videoSample.timeUs());
                videoSample = video.next();
            } else {
                writeSample(muxer, audioTrack, audioSample);
                audioEnd = Math.max(audioEnd, audioSample.timeUs());
                audioSample = audio.next();
            }
            progress("正在合成 MP4", percent(Math.max(videoEnd, audioEnd),
                    Math.max(video.durationUs(), audio.durationUs()), 99));
        }
        long tolerance = Math.max(3_000_000, Math.max(videoEnd, audioEnd) / 50);
        if (Math.abs(videoEnd - audioEnd) > tolerance
                || video.durationUs() - videoEnd > tolerance || audio.durationUs() - audioEnd > tolerance) {
            throw new IOException("音视频时长对不上。请确认两条轨道来自同一视频且缓存完整。");
        }
        progress("正在合成 MP4", 100);
    }

    private static void writeSample(Mp4Muxer muxer, int track, Mp4Track.Sample sample) throws MuxerException {
        byte[] data = sample.data();
        muxer.writeSampleData(track, ByteBuffer.wrap(data), new BufferInfo(sample.timeUs(), data.length, sample.flags()));
    }

    private void muxMkv(File videoFile, File audioFile, DanmakuAss.Script subtitles, File destination)
            throws IOException {
        try (Mp4Track video = Mp4Track.open(videoFile, C.TRACK_TYPE_VIDEO);
             Mp4Track audio = Mp4Track.open(audioFile, C.TRACK_TYPE_AUDIO);
             MkvMuxer muxer = new MkvMuxer(destination)) {
            Mp4Track.Sample videoSample = video.next();
            Mp4Track.Sample audioSample = audio.next();
            if (videoSample == null || audioSample == null || video.format() == null || audio.format() == null) {
                throw new IOException("所选缓存缺少可读取的音频或视频轨。");
            }
            checkLengthPrefixed(video.format(), videoSample.data());
            int videoTrack = muxer.addVideoTrack(video.format());
            int audioTrack = muxer.addAudioTrack(audio.format());
            int subtitleTrack = subtitles == null ? -1 : muxer.addSubtitleTrack(subtitles.header());
            List<DanmakuAss.Event> events = subtitles == null ? List.of() : subtitles.events();
            int nextEvent = 0;
            long videoEnd = 0;
            long audioEnd = 0;
            while (videoSample != null || audioSample != null || nextEvent < events.size()) {
                check();
                long videoTime = videoSample == null ? Long.MAX_VALUE : videoSample.timeUs();
                long audioTime = audioSample == null ? Long.MAX_VALUE : audioSample.timeUs();
                long subtitleTime = nextEvent < events.size()
                        ? events.get(nextEvent).startMs() * 1000 : Long.MAX_VALUE;
                if (subtitleTrack >= 0 && subtitleTime <= videoTime && subtitleTime <= audioTime) {
                    DanmakuAss.Event event = events.get(nextEvent);
                    muxer.writeSubtitle(subtitleTrack, event.startMs(),
                            Math.max(1, event.endMs() - event.startMs()),
                            DanmakuAss.blockPayload(event, nextEvent + 1));
                    nextEvent++;
                } else if (videoTime <= audioTime) {
                    muxer.writeSample(videoTrack, videoSample.timeUs(), videoSample.flags() != 0,
                            videoSample.data());
                    videoEnd = Math.max(videoEnd, videoSample.timeUs());
                    videoSample = video.next();
                } else {
                    muxer.writeSample(audioTrack, audioSample.timeUs(), audioSample.flags() != 0,
                            audioSample.data());
                    audioEnd = Math.max(audioEnd, audioSample.timeUs());
                    audioSample = audio.next();
                }
                progress("正在合成 MKV", percent(Math.max(videoEnd, audioEnd),
                        Math.max(video.durationUs(), audio.durationUs()), 99));
            }
            long tolerance = Math.max(3_000_000, Math.max(videoEnd, audioEnd) / 50);
            if (Math.abs(videoEnd - audioEnd) > tolerance
                    || video.durationUs() - videoEnd > tolerance || audio.durationUs() - audioEnd > tolerance) {
                throw new IOException("音视频时长对不上。请确认两条轨道来自同一视频且缓存完整。");
            }
            muxer.finish();
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new IOException("无法将所选轨道封装为 MKV。请检查缓存是否完整且可播放。", error);
        }
        progress("正在合成 MKV", 100);
    }

    /** MKV keeps the MP4-style length-prefixed NAL framing; reject anything else early. */
    private static void checkLengthPrefixed(Format format, byte[] firstSample) throws IOException {
        String mime = format.sampleMimeType;
        if (!MimeTypes.VIDEO_H264.equals(mime) && !MimeTypes.VIDEO_H265.equals(mime)) return;
        if (firstSample.length < 4) throw new IOException("视频数据不完整。");
        long length = ((long) firstSample[0] & 0xFF) << 24 | ((long) firstSample[1] & 0xFF) << 16
                | ((long) firstSample[2] & 0xFF) << 8 | ((long) firstSample[3] & 0xFF);
        if (length <= 0 || length > firstSample.length - 4L) {
            throw new IOException("该视频的码流格式暂不支持 MKV 封装。");
        }
    }

    static String displayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Some document providers do not expose a display name.
        }
        return fallback;
    }

    static String fileName(String title) {
        return fileName(title, false);
    }

    static String fileName(String title, boolean audio) {
        return fileName(title, audio, false);
    }

    static String fileName(String title, boolean audio, boolean mkv) {
        String name = safeName(title);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4a") || lower.endsWith(".mkv")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.codePointCount(0, name.length()) > 60) {
            name = name.substring(0, name.offsetByCodePoints(0, 60));
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "B站视频";
        return name + (audio ? ".m4a" : mkv ? ".mkv" : ".mp4");
    }

    static String stripExtension(String name) {
        return name.replaceFirst("(?i)\\.(mp4|mkv|m4a)$", "");
    }

    static String folderName(String title) {
        String name = safeName(title).replaceAll("^[. ]+|[. ]+$", "");
        if (name.codePointCount(0, name.length()) > 48) {
            name = name.substring(0, name.offsetByCodePoints(0, 48)).stripTrailing();
        }
        return name.isEmpty() ? "B站合集" : name;
    }

    private static String safeName(String title) {
        return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    }
}
