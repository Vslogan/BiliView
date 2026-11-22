package local.bilimp4.app;

import android.content.res.AssetFileDescriptor;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Reads the real media parameters of a cached track pair. The fast path parses the MP4
 * sample tables (moov box) directly — that yields duration, resolution, average frame
 * rate (stts sample count), exact per-track byte counts (stsz) and codec without
 * touching the media payload. Fragmented files without a sample table fall back to a
 * full pass with Mp4Track. An MD5 of the video file is computed separately so repeated
 * probes of identical content can reuse stored results.
 */
@OptIn(markerClass = UnstableApi.class)
final class MediaProbe {
    /** One track's measured parameters; fps and bitrate derive from sample tables or a scan. */
    record Track(String codec, int width, int height, float fps, int sampleRate, int channels,
                 long bytes, long samples, long durationMs) {
        long bitrateBps() {
            return durationMs > 0 ? bytes * 8000 / durationMs : 0;
        }
    }

    record Info(Track video, Track audio, String md5) {
        Info withMd5(String hash) {
            return new Info(video, audio, hash);
        }
    }

    private final File cacheDir;

    MediaProbe(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** Probes both tracks; either source may be null. MD5 of the video file is included. */
    Info probe(CacheEntry entry) throws IOException {
        Track video = entry.video() == null ? null : track(entry.video(), C.TRACK_TYPE_VIDEO);
        Track audio = entry.audio() == null ? null : track(entry.audio(), C.TRACK_TYPE_AUDIO);
        String md5 = entry.video() == null ? null : md5(entry.video());
        return new Info(video, audio, md5);
    }

    private Track track(ExportTask.Source source, int type) throws IOException {
        try (AssetFileDescriptor descriptor = source.open()) {
            if (descriptor == null) throw new IOException("无法打开缓存文件。");
            Track parsed = parse(descriptor, type == C.TRACK_TYPE_VIDEO);
            if (parsed != null) return parsed;
        }
        return scanFallback(source, type);
    }

    /** Fast path: parse the moov sample tables. Returns null when the file has none. */
    private static Track parse(AssetFileDescriptor afd, boolean wantVideo) throws IOException {
        long length = afd.getDeclaredLength();
        if (length < 0) length = afd.getParcelFileDescriptor().getStatSize();
        if (length <= 0) return null;
        long base = Math.max(0, afd.getStartOffset());
        FileChannel channel;
        try {
            channel = new FileInputStream(afd.getFileDescriptor()).getChannel();
        } catch (IllegalStateException | NullPointerException error) {
            return null;
        }
        long position = base + skipPrefix(channel, base, length);
        long end = base + length;
        Track track = null;
        while (position + 8 <= end) {
            long[] box = boxHeader(channel, position, end);
            if (box == null) break;
            String type = boxType(channel, position);
            if ("moov".equals(type)) {
                track = parseMoov(channel, box[0], box[1], wantVideo);
                break;
            }
            if (box[1] <= 0) break;
            position = box[0] + box[1];
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The descriptor itself is closed by the caller.
        }
        return track;
    }

    private static Track parseMoov(FileChannel channel, long moovStart, long moovSize, boolean wantVideo)
            throws IOException {
        long moovEnd = moovStart + moovSize;
        long position = moovStart + 8;
        Track track = null;
        while (position + 8 <= moovEnd) {
            long[] box = boxHeader(channel, position, moovEnd);
            if (box == null) break;
            String type = boxType(channel, position);
            if ("trak".equals(type)) {
                Track candidate = parseTrak(channel, box[0], box[1], wantVideo);
                if (candidate != null && (track == null || candidate.samples > track.samples)) {
                    track = candidate;
                }
            }
            if (box[1] <= 0) break;
            position = box[0] + box[1];
        }
        return track != null && track.samples > 0 ? track : null;
    }

    private static Track parseTrak(FileChannel channel, long trakStart, long trakSize, boolean wantVideo)
            throws IOException {
        long end = trakStart + trakSize;
        // descend trak → mdia, collecting mdhd + hdlr + minf/stbl/{stsd,stts,stsz}
        long mdiaStart = -1;
        long mdiaSize = 0;
        long position = trakStart + 8;
        while (position + 8 <= end) {
            long[] box = boxHeader(channel, position, end);
            if (box == null) break;
            if ("mdia".equals(boxType(channel, position))) {
                mdiaStart = box[0];
                mdiaSize = box[1];
                break;
            }
            if (box[1] <= 0) break;
            position = box[0] + box[1];
        }
        if (mdiaStart < 0) return null;

        String handler = "";
        long timescale = 0;
        long duration = 0;
        long stblStart = -1;
        long stblSize = 0;
        position = mdiaStart + 8;
        long mdiaEnd = mdiaStart + mdiaSize;
        while (position + 8 <= mdiaEnd) {
            long[] box = boxHeader(channel, position, mdiaEnd);
            if (box == null) break;
            String type = boxType(channel, position);
            if ("mdhd".equals(type)) {
                ByteBuffer mdhd = read(channel, position + 8, Math.min(box[1] - 8, 32));
                int version = mdhd.get(0) & 0xFF;
                if (version == 1 && mdhd.remaining() >= 32) {
                    timescale = uint(mdhd, 20);
                    duration = mdhd.getLong(24);
                } else if (mdhd.remaining() >= 20) {
                    timescale = uint(mdhd, 12);
                    duration = uint(mdhd, 16);
                }
            } else if ("hdlr".equals(type)) {
                ByteBuffer hdlr = read(channel, position + 8, Math.min(box[1] - 8, 16));
                if (hdlr.remaining() >= 12) {
                    handler = ascii(hdlr, 8, 4);
                }
            } else if ("minf".equals(type)) {
                long[] stbl = findBox(channel, box[0], box[1], "stbl");
                if (stbl != null) {
                    stblStart = stbl[0];
                    stblSize = stbl[1];
                }
            }
            if (box[1] <= 0) break;
            position = box[0] + box[1];
        }
        boolean videoTrack = "vide".equals(handler);
        boolean audioTrack = "soun".equals(handler);
        if (stblStart < 0 || !(videoTrack || audioTrack)) return null;

        String codec = "";
        int width = 0;
        int height = 0;
        int sampleRate = 0;
        int channels = 0;
        long samples = 0;
        long bytes = 0;
        position = stblStart + 8;
        long stblEnd = stblStart + stblSize;
        while (position + 8 <= stblEnd) {
            long[] box = boxHeader(channel, position, stblEnd);
            if (box == null) break;
            String type = boxType(channel, position);
            long content = box[0] + headerSize(channel, box[0]);
            if ("stsd".equals(type)) {
                long[] entry = stsdEntry(channel, content, box[0] + box[1]);
                if (entry != null) {
                    codec = boxType(channel, entry[0]);
                    long payload = entry[0] + 8;
                    if (videoTrack && entry[1] >= 86) {
                        ByteBuffer dims = read(channel, payload + 24, 8);
                        width = unsignedShort(dims, 0);
                        height = unsignedShort(dims, 2);
                    } else if (audioTrack && entry[1] >= 36) {
                        ByteBuffer audio = read(channel, payload + 16, 10);
                        channels = unsignedShort(audio, 0);
                        sampleRate = (int) (uint(audio, 8) >> 16);
                    }
                }
            } else if ("stts".equals(type)) {
                ByteBuffer stts = read(channel, content, Math.min(box[1] - headerSize(channel, box[0]), 8));
                long entryCount = uint(stts, 4);
                // sample counts only; cap the table read at a sane size
                long tableBytes = Math.min(entryCount * 8, 8L * 1024 * 1024);
                ByteBuffer table = read(channel, content + 8, tableBytes);
                for (long i = 0; i < tableBytes / 8; i++) {
                    samples += uint(table, (int) (i * 8));
                }
            } else if ("stsz".equals(type)) {
                ByteBuffer stsz = read(channel, content, 12);
                long sampleSize = uint(stsz, 4);
                long sampleCount = uint(stsz, 8);
                if (sampleSize > 0) {
                    bytes = sampleSize * sampleCount;
                } else {
                    long tableBytes = Math.min(sampleCount * 4, 4L * 1024 * 1024);
                    ByteBuffer table = read(channel, content + 12, tableBytes);
                    for (long i = 0; i < tableBytes / 4; i++) {
                        bytes += uint(table, (int) (i * 4));
                    }
                }
                samples = Math.max(samples, sampleCount);
            }
            if (box[1] <= 0) break;
            position = box[0] + box[1];
        }
        if (codec.isEmpty()) return null;
        long durationMs = timescale > 0 ? duration * 1000 / timescale : 0;
        float fps = duration > 0 && timescale > 0 ? (float) (samples * (double) timescale / duration) : 0;
        if (videoTrack != wantVideo) return null;
        return new Track(codec, width, height, videoTrack ? fps : 0, sampleRate, channels, bytes, samples, durationMs);
    }

    /** Fragmented or table-less files: count samples and bytes in one full pass. */
    private Track scanFallback(ExportTask.Source source, int type) throws IOException {
        File temp = File.createTempFile("bili-probe-", ".m4s", cacheDir);
        try {
            try (AssetFileDescriptor descriptor = source.open();
                 InputStream input = descriptor.createInputStream();
                 java.io.FileOutputStream output = new FileOutputStream(temp)) {
                byte[] prefix = new byte[17];
                int count = 0;
                while (count < prefix.length) {
                    int read = input.read(prefix, count, prefix.length - count);
                    if (read == -1) break;
                    count += read;
                }
                int skip = ExportTask.cachePrefix(prefix, count);
                output.write(prefix, skip, count - skip);
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            try (Mp4Track track = Mp4Track.open(temp, type)) {
                Mp4Track.Sample sample = track.next();
                if (sample == null || track.format() == null) return null;
                Format format = track.format();
                long samples = 0;
                long bytes = 0;
                while (sample != null) {
                    samples++;
                    bytes += sample.data().length;
                    sample = track.next();
                }
                long durationUs = track.durationUs();
                long durationMs = durationUs > 0 ? durationUs / 1000 : 0;
                float fps = durationUs > 0 ? (float) (samples * 1_000_000.0 / durationUs) : 0;
                String codec = codecName(format.sampleMimeType);
                return new Track(codec, Math.max(0, format.width), Math.max(0, format.height),
                        type == C.TRACK_TYPE_VIDEO ? fps : 0,
                        Math.max(0, format.sampleRate), Math.max(0, format.channelCount),
                        bytes, samples, durationMs);
            }
        } finally {
            temp.delete();
        }
    }

    static String md5(ExportTask.Source source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("当前系统不支持 MD5。", error);
        }
        try (AssetFileDescriptor descriptor = source.open();
             InputStream input = descriptor.createInputStream()) {
            if (input == null) throw new IOException("无法打开缓存文件。");
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        byte[] hash = digest.digest();
        StringBuilder text = new StringBuilder();
        for (byte value : hash) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }

    static String codecName(String fourccOrMime) {
        if (fourccOrMime == null) return "?";
        return switch (fourccOrMime) {
            case "avc1", "avc3", "video/avc" -> "H.264";
            case "hvc1", "hev1", "video/hevc" -> "H.265";
            case "av01", "video/av01" -> "AV1";
            case "vp09", "video/x-vnd.on2.vp9" -> "VP9";
            case "mp4a", "audio/mp4a-latm" -> "AAC";
            case "ac-3", "audio/ac3" -> "AC-3";
            case "ec-3", "audio/eac3" -> "E-AC-3";
            case "opus", "audio/opus" -> "Opus";
            case "flac", "audio/flac" -> "FLAC";
            default -> fourccOrMime;
        };
    }

    // ---------- MP4 box helpers ----------

    private static long[] boxHeader(FileChannel channel, long position, long limit) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8);
        if (readFully(channel, header, position) < 8) return null;
        long size = uint(header, 0);
        if (size == 1) {
            ByteBuffer extended = ByteBuffer.allocate(8);
            if (read(channel, extended, position + 8) < 8) return null;
            size = extended.getLong(0);
        } else if (size == 0) {
            size = limit - position;
        }
        if (size < 8 || position + size > limit) return null;
        return new long[]{position, size};
    }

    private static long headerSize(FileChannel channel, long position) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8);
        if (read(channel, header, position) < 8) return 8;
        return uint(header, 0) == 1 ? 16 : 8;
    }

    private static String boxType(FileChannel channel, long position) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8);
        if (read(channel, header, position) < 8) return "";
        return ascii(header, 4, 4);
    }

    private static long[] findBox(FileChannel channel, long start, long size, String wanted) throws IOException {
        long end = start + size;
        long position = start + 8;
        while (position + 8 <= end) {
            long[] box = boxHeader(channel, position, end);
            if (box == null) break;
            if (wanted.equals(boxType(channel, position))) return box;
            if (box[1] <= 0) break;
            position = box[0] + box[1];
        }
        return null;
    }

    private static long[] stsdEntry(FileChannel channel, long content, long limit) throws IOException {
        ByteBuffer head = read(channel, content, 8);
        if (uint(head, 4) == 0) return null;
        long entry = content + 8;
        long[] box = boxHeader(channel, entry, limit);
        return box;
    }

    private static int skipPrefix(FileChannel channel, long base, long length) throws IOException {
        ByteBuffer prefix = ByteBuffer.allocate(17);
        int count = read(channel, prefix, base);
        return ExportTask.cachePrefix(prefix.array(), Math.max(0, count));
    }

    private static ByteBuffer read(FileChannel channel, long position, long length) throws IOException {
        if (length > 64 * 1024 * 1024) throw new IOException("媒体元数据过大。");
        ByteBuffer buffer = ByteBuffer.allocate((int) length);
        readFully(channel, buffer, position);
        buffer.flip();
        return buffer;
    }

    private static int read(FileChannel channel, ByteBuffer target, long position) throws IOException {
        target.clear();
        int total = 0;
        while (target.hasRemaining()) {
            int read = channel.read(target, position + total);
            if (read < 0) break;
            total += read;
        }
        target.flip();
        return total;
    }

    private static int readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
        int total = 0;
        while (target.hasRemaining()) {
            int read = channel.read(target, position + total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private static long uint(ByteBuffer buffer, int offset) {
        return buffer.getInt(offset) & 0xFFFF_FFFFL;
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return buffer.getShort(offset) & 0xFFFF;
    }

    private static String ascii(ByteBuffer buffer, int offset, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = buffer.get(offset + i);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
