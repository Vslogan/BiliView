package local.bilimp4.app;

import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.NalUnitUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal streaming Matroska writer for the track set Bilibili serves: H.264/H.265/AV1/VP9
 * video, AAC/AC-3/E-AC-3 audio and ASS subtitles. Sizes that are unknown while writing
 * (segment and cluster lengths, total duration) are reserved as eight-byte fields and
 * patched in place at the end, and a cue index is appended so the result stays seekable.
 * Video and audio samples keep their MP4 (AVCC/HVCC) framing, matching the Matroska
 * ISO/AVC and ISO/HEVC codec mappings, so no re-encoding or bitstream conversion happens.
 */
@OptIn(markerClass = UnstableApi.class)
final class MkvMuxer implements AutoCloseable {
    private static final byte[] ID_EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final byte[] ID_EBML_VERSION = {0x42, (byte) 0x86};
    private static final byte[] ID_EBML_READ_VERSION = {0x42, (byte) 0xF7};
    private static final byte[] ID_EBML_MAX_ID_LENGTH = {0x42, (byte) 0xF2};
    private static final byte[] ID_EBML_MAX_SIZE_LENGTH = {0x42, (byte) 0xF3};
    private static final byte[] ID_DOC_TYPE = {0x42, (byte) 0x82};
    private static final byte[] ID_DOC_TYPE_VERSION = {0x42, (byte) 0x87};
    private static final byte[] ID_DOC_TYPE_READ_VERSION = {0x42, (byte) 0x85};
    private static final byte[] ID_SEGMENT = {0x18, 0x53, (byte) 0x80, 0x67};
    private static final byte[] ID_INFO = {0x15, 0x49, (byte) 0xA9, 0x66};
    private static final byte[] ID_TIMESTAMP_SCALE = {0x2A, (byte) 0xD7, (byte) 0xB1};
    private static final byte[] ID_MUXING_APP = {0x4D, (byte) 0x80};
    private static final byte[] ID_WRITING_APP = {0x57, 0x41};
    private static final byte[] ID_DURATION = {0x44, (byte) 0x89};
    private static final byte[] ID_TRACKS = {0x16, 0x54, (byte) 0xAE, 0x6B};
    private static final byte[] ID_TRACK_ENTRY = {(byte) 0xAE};
    private static final byte[] ID_TRACK_NUMBER = {(byte) 0xD7};
    private static final byte[] ID_TRACK_UID = {0x73, (byte) 0xC5};
    private static final byte[] ID_TRACK_TYPE = {(byte) 0x83};
    private static final byte[] ID_FLAG_ENABLED = {(byte) 0xB9};
    private static final byte[] ID_FLAG_DEFAULT = {(byte) 0x88};
    private static final byte[] ID_FLAG_LACING = {(byte) 0x9C};
    private static final byte[] ID_LANGUAGE = {0x22, (byte) 0xB5, (byte) 0x9C};
    private static final byte[] ID_CODEC_ID = {(byte) 0x86};
    private static final byte[] ID_CODEC_PRIVATE = {0x63, (byte) 0xA2};
    private static final byte[] ID_NAME = {0x53, 0x6E};
    private static final byte[] ID_VIDEO = {(byte) 0xE0};
    private static final byte[] ID_PIXEL_WIDTH = {(byte) 0xB0};
    private static final byte[] ID_PIXEL_HEIGHT = {(byte) 0xBA};
    private static final byte[] ID_AUDIO = {(byte) 0xE1};
    private static final byte[] ID_SAMPLING_FREQUENCY = {(byte) 0xB5};
    private static final byte[] ID_CHANNELS = {(byte) 0x9F};
    private static final byte[] ID_CLUSTER = {0x1F, 0x43, (byte) 0xB6, 0x75};
    private static final byte[] ID_TIMESTAMP = {(byte) 0xE7};
    private static final byte[] ID_SIMPLE_BLOCK = {(byte) 0xA3};
    private static final byte[] ID_BLOCK_GROUP = {(byte) 0xA0};
    private static final byte[] ID_BLOCK = {(byte) 0xA1};
    private static final byte[] ID_BLOCK_DURATION = {(byte) 0x9B};
    private static final byte[] ID_CUES = {0x1C, 0x53, (byte) 0xBB, 0x6B};
    private static final byte[] ID_CUE_POINT = {(byte) 0xBB};
    private static final byte[] ID_CUE_TIME = {(byte) 0xB3};
    private static final byte[] ID_CUE_TRACK_POSITIONS = {(byte) 0xB7};
    private static final byte[] ID_CUE_TRACK = {(byte) 0xF7};
    private static final byte[] ID_CUE_CLUSTER_POSITION = {(byte) 0xF1};

    private static final int TRACK_TYPE_VIDEO = 1;
    private static final int TRACK_TYPE_AUDIO = 2;
    private static final int TRACK_TYPE_SUBTITLE = 0x11;
    private static final long TIMESTAMP_SCALE = 1_000_000L; // one tick is one millisecond
    private static final long MAX_RELATIVE_MS = 30_000;     // block timestamps are signed 16-bit
    private static final long MIN_CLUSTER_MS = 2_000;       // split clusters at keyframes past this
    private static final int MAX_CUES = 20_000;

    private final RandomAccessFile file;
    private final long segmentSizePosition;
    private final long segmentDataStart;
    private final List<byte[]> trackEntries = new ArrayList<>();
    private final ByteArrayOutputStream cues = new ByteArrayOutputStream();
    private long durationPosition = -1;
    private long clusterElementStart = -1;
    private long clusterSizePosition;
    private long clusterTimeMs;
    private long maxTimeMs;
    private int cueCount;
    private int videoTrack = -1;
    private boolean headersWritten;
    private boolean finished;

    MkvMuxer(File target) throws IOException {
        file = new RandomAccessFile(target, "rw");
        file.setLength(0);
        file.write(ebmlHeader());
        file.write(ID_SEGMENT);
        segmentSizePosition = file.getFilePointer();
        file.write(new byte[8]); // segment size, patched in finish()
        segmentDataStart = file.getFilePointer();
    }

    int addVideoTrack(Format format) throws IOException {
        String mime = format.sampleMimeType == null ? "" : format.sampleMimeType;
        String codec;
        byte[] codecPrivate;
        switch (mime) {
            case MimeTypes.VIDEO_H264 -> {
                codec = "V_MPEG4/ISO/AVC";
                codecPrivate = avcConfig(format);
            }
            case MimeTypes.VIDEO_H265 -> {
                codec = "V_MPEGH/ISO/HEVC";
                codecPrivate = hevcConfig(format);
            }
            case MimeTypes.VIDEO_AV1 -> {
                codec = "V_AV1";
                codecPrivate = format.initializationData.isEmpty() ? null : format.initializationData.get(0);
            }
            case MimeTypes.VIDEO_VP9 -> {
                codec = "V_VP9";
                codecPrivate = null;
            }
            default -> throw new IOException("MKV 封装暂不支持该视频编码：" + mime);
        }
        Buf settings = new Buf();
        settings.elUint(ID_PIXEL_WIDTH, Math.max(1, format.width));
        settings.elUint(ID_PIXEL_HEIGHT, Math.max(1, format.height));
        int number = addTrack(TRACK_TYPE_VIDEO, codec, codecPrivate, "und", null, ID_VIDEO, settings.toByteArray());
        videoTrack = number;
        return number;
    }

    int addAudioTrack(Format format) throws IOException {
        String mime = format.sampleMimeType == null ? "" : format.sampleMimeType;
        String codec;
        byte[] codecPrivate;
        switch (mime) {
            case MimeTypes.AUDIO_AAC -> {
                codec = "A_AAC";
                if (format.initializationData.isEmpty()) {
                    throw new IOException("音频编码参数缺失，无法封装 MKV。");
                }
                codecPrivate = format.initializationData.get(0);
            }
            case MimeTypes.AUDIO_AC3 -> {
                codec = "A_AC3";
                codecPrivate = null;
            }
            case MimeTypes.AUDIO_E_AC3 -> {
                codec = "A_EAC3";
                codecPrivate = null;
            }
            default -> throw new IOException("MKV 封装暂不支持该音频编码：" + mime);
        }
        Buf settings = new Buf();
        settings.elFloat(ID_SAMPLING_FREQUENCY, format.sampleRate);
        settings.elUint(ID_CHANNELS, Math.max(1, format.channelCount));
        return addTrack(TRACK_TYPE_AUDIO, codec, codecPrivate, "und", null, ID_AUDIO, settings.toByteArray());
    }

    int addSubtitleTrack(String assHeader) {
        return addTrack(TRACK_TYPE_SUBTITLE, "S_TEXT/ASS",
                assHeader.getBytes(StandardCharsets.UTF_8), "chi", "B站弹幕", null, null);
    }

    private int addTrack(int type, String codec, byte[] codecPrivate, String language, String name,
                         byte[] settingsId, byte[] settings) {
        if (headersWritten) throw new IllegalStateException("轨道必须在写入数据前登记。");
        int number = trackEntries.size() + 1;
        Buf entry = new Buf();
        entry.elUint(ID_TRACK_NUMBER, number);
        entry.elUint(ID_TRACK_UID, 42000L + number);
        entry.elUint(ID_TRACK_TYPE, type);
        entry.elUint(ID_FLAG_ENABLED, 1);
        entry.elUint(ID_FLAG_DEFAULT, 1);
        entry.elUint(ID_FLAG_LACING, 0);
        entry.elStr(ID_LANGUAGE, language);
        entry.elStr(ID_CODEC_ID, codec);
        if (codecPrivate != null) entry.el(ID_CODEC_PRIVATE, codecPrivate);
        if (name != null) entry.elStr(ID_NAME, name);
        if (settingsId != null) entry.el(settingsId, settings);
        trackEntries.add(entry.toByteArray());
        return number;
    }

    private void writeHeaders() throws IOException {
        if (headersWritten) return;
        headersWritten = true;
        Buf info = new Buf();
        info.elUint(ID_TIMESTAMP_SCALE, TIMESTAMP_SCALE);
        info.elStr(ID_MUXING_APP, "BiliView");
        info.elStr(ID_WRITING_APP, "BiliView " + BuildConfig.VERSION_NAME);
        info.id(ID_DURATION).size(8);
        Buf infoElement = new Buf();
        infoElement.id(ID_INFO).size(info.size() + 8).write(info.toByteArray(), 0, info.size());
        infoElement.write(new byte[8], 0, 8); // duration, patched in finish()
        long infoStart = file.getFilePointer();
        file.write(infoElement.toByteArray());
        durationPosition = infoStart + infoElement.size() - 8;

        Buf tracks = new Buf();
        for (byte[] entry : trackEntries) tracks.el(ID_TRACK_ENTRY, entry);
        file.write(ID_TRACKS);
        file.write(Buf.sizeBytes(tracks.size()));
        file.write(tracks.toByteArray());
    }

    void writeSample(int track, long timeUs, boolean keyframe, byte[] data) throws IOException {
        writeHeaders();
        long ms = Math.max(0, Math.round(timeUs / 1000.0));
        boolean splitAtKeyframe = track == videoTrack && keyframe && clusterElementStart >= 0
                && ms - clusterTimeMs >= MIN_CLUSTER_MS;
        if (clusterElementStart < 0 || ms - clusterTimeMs > MAX_RELATIVE_MS || splitAtKeyframe) {
            closeCluster();
            openCluster(ms);
        }
        file.write(ID_SIMPLE_BLOCK);
        file.write(Buf.sizeBytes(data.length + 4));
        file.write(0x80 | track);
        file.writeShort((int) (ms - clusterTimeMs));
        file.write(keyframe ? 0x80 : 0x00);
        file.write(data);
        if (track == videoTrack && keyframe) addCue(ms);
        maxTimeMs = Math.max(maxTimeMs, ms);
    }

    void writeSubtitle(int track, long startMs, long durationMs, byte[] payload) throws IOException {
        writeHeaders();
        if (clusterElementStart < 0 || startMs - clusterTimeMs > MAX_RELATIVE_MS) {
            closeCluster();
            openCluster(startMs);
        }
        int relative = (int) (startMs - clusterTimeMs);
        Buf block = new Buf();
        block.write(0x80 | track);
        block.write((relative >>> 8) & 0xFF);
        block.write(relative & 0xFF);
        block.write(0);
        block.write(payload, 0, payload.length);
        Buf group = new Buf();
        group.el(ID_BLOCK, block.toByteArray());
        group.elUint(ID_BLOCK_DURATION, Math.max(0, durationMs));
        file.write(ID_BLOCK_GROUP);
        file.write(Buf.sizeBytes(group.size()));
        file.write(group.toByteArray());
        maxTimeMs = Math.max(maxTimeMs, startMs + Math.max(0, durationMs));
    }

    /** Appends the cue index and patches the reserved size and duration fields. */
    void finish() throws IOException {
        if (finished) return;
        finished = true;
        closeCluster();
        if (cues.size() > 0) {
            file.write(ID_CUES);
            file.write(Buf.sizeBytes(cues.size()));
            file.write(cues.toByteArray());
        }
        long end = file.getFilePointer();
        file.seek(segmentSizePosition);
        file.write(Buf.size8(end - segmentDataStart));
        if (durationPosition > 0) {
            file.seek(durationPosition);
            file.writeDouble(maxTimeMs / 1000.0);
        }
        file.seek(end);
    }

    @Override public void close() throws IOException {
        file.close();
    }

    private void openCluster(long timeMs) throws IOException {
        clusterElementStart = file.getFilePointer();
        file.write(ID_CLUSTER);
        clusterSizePosition = file.getFilePointer();
        file.write(new byte[8]); // cluster size, patched in closeCluster()
        clusterTimeMs = timeMs;
        Buf timestamp = new Buf();
        timestamp.elUint(ID_TIMESTAMP, timeMs);
        file.write(timestamp.toByteArray());
    }

    private void closeCluster() throws IOException {
        if (clusterElementStart < 0) return;
        long end = file.getFilePointer();
        file.seek(clusterSizePosition);
        file.write(Buf.size8(end - clusterSizePosition - 8));
        file.seek(end);
        clusterElementStart = -1;
    }

    private void addCue(long timeMs) {
        if (cueCount >= MAX_CUES || videoTrack < 0) return;
        Buf positions = new Buf();
        positions.elUint(ID_CUE_TRACK, videoTrack);
        positions.elUint(ID_CUE_CLUSTER_POSITION, clusterElementStart - segmentDataStart);
        Buf point = new Buf();
        point.elUint(ID_CUE_TIME, timeMs);
        point.el(ID_CUE_TRACK_POSITIONS, positions.toByteArray());
        cues.write(ID_CUE_POINT, 0, ID_CUE_POINT.length);
        byte[] size = Buf.sizeBytes(point.size());
        cues.write(size, 0, size.length);
        cues.write(point.toByteArray(), 0, point.size());
        cueCount++;
    }

    private static byte[] ebmlHeader() {
        Buf body = new Buf();
        body.elUint(ID_EBML_VERSION, 1);
        body.elUint(ID_EBML_READ_VERSION, 1);
        body.elUint(ID_EBML_MAX_ID_LENGTH, 4);
        body.elUint(ID_EBML_MAX_SIZE_LENGTH, 8);
        body.elStr(ID_DOC_TYPE, "matroska");
        body.elUint(ID_DOC_TYPE_VERSION, 4);
        body.elUint(ID_DOC_TYPE_READ_VERSION, 2);
        Buf out = new Buf();
        out.el(ID_EBML, body.toByteArray());
        return out.toByteArray();
    }

    /** Builds the AVCDecoderConfigurationRecord from the extractor's Annex-B CSD buffers. */
    private static byte[] avcConfig(Format format) throws IOException {
        if (format.initializationData.size() < 2) {
            throw new IOException("视频编码参数缺失，无法封装 MKV。");
        }
        List<byte[]> spsList = annexB(format.initializationData.get(0));
        List<byte[]> ppsList = annexB(format.initializationData.get(1));
        if (spsList.isEmpty() || ppsList.isEmpty()) {
            throw new IOException("视频编码参数缺失，无法封装 MKV。");
        }
        NalUnitUtil.SpsData sps;
        try {
            sps = NalUnitUtil.parseSpsNalUnit(spsList.get(0), 0, spsList.get(0).length);
        } catch (RuntimeException error) {
            throw new IOException("无法解析 H.264 编码参数。", error);
        }
        Buf out = new Buf();
        out.write(1);
        out.write(sps.profileIdc);
        out.write(sps.constraintsFlagsAndReservedZero2Bits);
        out.write(sps.levelIdc);
        out.write(0xFF); // 4-byte NAL lengths, matching the sample framing
        out.write(0xE0 | Math.min(spsList.size(), 31));
        for (byte[] unit : spsList) {
            out.writeShort(unit.length);
            out.write(unit, 0, unit.length);
        }
        out.write(Math.min(ppsList.size(), 255));
        for (byte[] unit : ppsList) {
            out.writeShort(unit.length);
            out.write(unit, 0, unit.length);
        }
        return out.toByteArray();
    }

    /** Builds the HEVCDecoderConfigurationRecord from the Annex-B VPS/SPS/PPS buffer. */
    private static byte[] hevcConfig(Format format) throws IOException {
        if (format.initializationData.isEmpty()) {
            throw new IOException("视频编码参数缺失，无法封装 MKV。");
        }
        List<byte[]> units = annexB(format.initializationData.get(0));
        if (units.size() < 2 || units.get(0).length < 18 || (units.get(0)[0] & 0x7E) != 0x40) {
            throw new IOException("H.265 编码参数不完整，无法封装 MKV。");
        }
        byte[] vps = units.get(0);
        NalUnitUtil.H265SpsData sps;
        try {
            sps = NalUnitUtil.parseH265SpsNalUnit(units.get(1), 0, units.get(1).length, null);
        } catch (RuntimeException error) {
            throw new IOException("无法解析 H.265 编码参数。", error);
        }
        Buf out = new Buf();
        out.write(1); // configurationVersion
        out.write(vps[6] & 0xFF); // profile space, tier and profile idc
        out.writeInt32(vps, 7);   // general_profile_compatibility_flags
        out.writeInt32(vps, 11);  // general_constraint_indicator_flags (high 4 bytes)
        out.writeShort(((vps[15] & 0xFF) << 8) | (vps[16] & 0xFF));
        out.write(vps[17] & 0xFF); // general_level_idc
        out.writeShort(0xF000); // unknown spatial segmentation
        out.write(0xFC); // unknown parallelism
        out.write(0xFC | (sps.chromaFormatIdc & 3));
        out.write(0xF8 | (sps.bitDepthLumaMinus8 & 7));
        out.write(0xF8 | (sps.bitDepthChromaMinus8 & 7));
        out.writeShort(0); // avgFrameRate unspecified
        out.write(0x0F); // lengthSizeMinusOne = 3, matching the sample framing
        out.write(units.size());
        for (byte[] unit : units) {
            out.write((unit[0] >> 1) & 0x3F); // NAL unit type
            out.writeShort(1);
            out.writeShort(unit.length);
            out.write(unit, 0, unit.length);
        }
        return out.toByteArray();
    }

    /** Splits an Annex-B buffer into its NAL units, dropping the start codes. */
    private static List<byte[]> annexB(byte[] data) {
        List<byte[]> units = new ArrayList<>();
        int nalStart = -1;
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1
                    || (data[i + 2] == 0 && i + 3 < data.length && data[i + 3] == 1))) {
                if (nalStart >= 0 && nalStart < i) {
                    units.add(java.util.Arrays.copyOfRange(data, nalStart, i));
                }
                nalStart = data[i + 2] == 1 ? i + 3 : i + 4;
                i = nalStart - 1;
            }
        }
        if (nalStart >= 0 && nalStart < data.length) {
            units.add(java.util.Arrays.copyOfRange(data, nalStart, data.length));
        }
        if (units.isEmpty() && data.length > 0) units.add(data); // already a bare NAL
        return units;
    }

    /** ByteArrayOutputStream with EBML primitives; nothing here throws. */
    private static final class Buf extends ByteArrayOutputStream {
        Buf id(byte[] elementId) {
            write(elementId, 0, elementId.length);
            return this;
        }

        Buf size(long value) {
            byte[] bytes = sizeBytes(value);
            write(bytes, 0, bytes.length);
            return this;
        }

        Buf el(byte[] elementId, byte[] payload) {
            id(elementId).size(payload.length);
            write(payload, 0, payload.length);
            return this;
        }

        Buf elUint(byte[] elementId, long value) {
            return el(elementId, uintBytes(value));
        }

        Buf elStr(byte[] elementId, String value) {
            return el(elementId, value.getBytes(StandardCharsets.UTF_8));
        }

        Buf elFloat(byte[] elementId, double value) {
            long bits = Double.doubleToLongBits(value);
            byte[] bytes = new byte[8];
            for (int i = 0; i < 8; i++) bytes[7 - i] = (byte) (bits >>> (8 * i));
            return el(elementId, bytes);
        }

        void writeShort(int value) {
            write((value >>> 8) & 0xFF);
            write(value & 0xFF);
        }

        void writeInt32(byte[] data, int offset) {
            write(data, offset, 4);
        }

        static byte[] uintBytes(long value) {
            int length = Math.max(1, (64 - Long.numberOfLeadingZeros(value) + 7) / 8);
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) out[length - 1 - i] = (byte) (value >>> (8 * i));
            return out;
        }

        static byte[] sizeBytes(long value) {
            int length = 1;
            while (length < 8 && value >= (1L << (7 * length)) - 1) length++;
            return sizeWithLength(value, length);
        }

        static byte[] size8(long value) {
            return sizeWithLength(value, 8);
        }

        private static byte[] sizeWithLength(long value, int length) {
            long encoded = value | (1L << (7 * length));
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) out[length - 1 - i] = (byte) (encoded >>> (8 * i));
            return out;
        }
    }
}
