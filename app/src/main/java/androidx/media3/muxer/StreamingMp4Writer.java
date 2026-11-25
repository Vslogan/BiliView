package androidx.media3.muxer;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A streaming MP4 writer that produces a regular (non-fragmented) MP4 without holding
 * sample data in memory: sample bytes go straight to the file while only per-sample
 * metadata is kept, then the moov table is written at the end and the 64-bit mdat size
 * is patched in place. This mirrors the non-fragmented branch of {@link Boxes#moov}
 * (which backs the platform-style in-memory muxer) so the output keeps the same box
 * layout, timescales and edit lists, but works for multi-GB files.
 *
 * <p>This class lives in the media3 muxer package because {@link Boxes} and
 * {@link BoxUtils} are package private; it only calls their public static methods.
 */
@OptIn(markerClass = UnstableApi.class)
public final class StreamingMp4Writer implements AutoCloseable {

    /** Track timescales, matching the media3 muxer (see Track#videoUnitTimebase). */
    private static final int VIDEO_TIMESCALE = 90_000;
    private static final int AUDIO_TIMESCALE = 48_000;
    /** Movie timescale, matching Boxes#MVHD_TIMEBASE. */
    private static final long MVHD_TIMESCALE = 10_000L;
    private static final long UINT32_MAX = 0xFFFFFFFFL;
    private static final int WRITE_BUFFER_BYTES = 512 * 1024;

    private static final class TrackState {
        final Format format;
        final List<BufferInfo> samples = new ArrayList<>();
        final List<Long> offsets = new ArrayList<>();
        long firstPtsUs = C.TIME_UNSET;

        TrackState(Format format) {
            this.format = format;
        }
    }

    private final RandomAccessFile file;
    private final FileChannel channel;
    private final ByteBuffer pending = ByteBuffer.allocate(WRITE_BUFFER_BYTES);
    private final List<TrackState> tracks = new ArrayList<>();
    private final long mdatHeaderPosition;
    private long flushed;
    private boolean finished;

    public StreamingMp4Writer(File output) throws IOException {
        file = new RandomAccessFile(output, "rw");
        file.setLength(0);
        channel = file.getChannel();
        writeFlushed(Boxes.ftyp());
        // mdat with a 64-bit largesize, patched in finish() once the size is known.
        mdatHeaderPosition = flushed;
        ByteBuffer header = ByteBuffer.allocate(16);
        header.putInt(1);
        header.putInt(0x6d646174); // "mdat"
        header.putLong(0);
        header.flip();
        writeFlushed(header);
    }

    /** Adds a track; samples for it may then be written in any interleaving. */
    public int addTrack(Format format) {
        tracks.add(new TrackState(format));
        return tracks.size() - 1;
    }

    /** Writes one sample. Metadata is kept in memory; data is buffered then flushed. */
    public void writeSample(int trackIndex, byte[] data, long ptsUs, boolean keyFrame)
            throws IOException {
        if (finished) throw new IllegalStateException("already finished");
        TrackState track = tracks.get(trackIndex);
        long offset = flushed + pending.position();
        if (data.length > pending.remaining()) {
            flush();
        }
        if (data.length > pending.remaining()) {
            // A single sample larger than the buffer goes straight to the file.
            writeFlushed(ByteBuffer.wrap(data));
        } else {
            pending.put(data);
        }
        track.offsets.add(offset);
        track.samples.add(new BufferInfo(ptsUs, data.length,
                keyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0));
        if (track.firstPtsUs == C.TIME_UNSET) track.firstPtsUs = ptsUs;
    }

    /** Writes the moov table and patches the mdat size. Must be called before close(). */
    public void finish() throws IOException {
        if (finished) return;
        flush();
        long dataEnd = flushed;
        long mdatSize = dataEnd - mdatHeaderPosition;
        channel.position(mdatHeaderPosition + 8); // largesize field
        ByteBuffer sizeField = ByteBuffer.allocate(8);
        sizeField.putLong(mdatSize);
        sizeField.flip();
        while (sizeField.hasRemaining()) channel.write(sizeField);
        channel.position(dataEnd);
        writeFlushed(moov());
        finished = true;
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            file.close();
        }
    }

    // ---------- moov assembly, mirroring the non-fragmented branch of Boxes.moov ----------

    private ByteBuffer moov() {
        long minInputPtsUs = Long.MAX_VALUE;
        for (TrackState track : tracks) {
            if (!track.samples.isEmpty()) minInputPtsUs = Math.min(minInputPtsUs, track.firstPtsUs);
        }
        if (minInputPtsUs == Long.MAX_VALUE) minInputPtsUs = 0;

        List<ByteBuffer> trakBoxes = new ArrayList<>();
        long maxPresentationDurationUs = 0;
        int nextTrackId = 1;
        for (TrackState track : tracks) {
            if (track.samples.isEmpty()) continue;
            Format format = track.format;
            if (MimeTypes.VIDEO_AV1.equals(format.sampleMimeType) && format.initializationData.isEmpty()) {
                throw new IllegalStateException("AV1 轨道缺少编码配置，请改用 MKV 导出。");
            }
            boolean audio = MimeTypes.isAudio(format.sampleMimeType);
            int timescale = audio ? AUDIO_TIMESCALE : VIDEO_TIMESCALE;
            List<Integer> durationsVu = Boxes.convertPresentationTimestampsToDurationsVu(
                    track.samples, timescale,
                    Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS,
                    C.TIME_UNSET);
            long trackDurationVu = 0;
            for (int duration : durationsVu) trackDurationVu += duration;
            long trackDurationUs = trackDurationVu * C.MICROS_PER_SECOND / timescale;
            long firstPtsUs = track.firstPtsUs == C.TIME_UNSET ? 0 : track.firstPtsUs;
            long presentationDurationUs =
                    firstPtsUs < 0 ? trackDurationUs - Math.abs(firstPtsUs) : trackDurationUs;

            ByteBuffer stts = Boxes.stts(durationsVu);
            ByteBuffer stsz = Boxes.stsz(track.samples);
            // One sample per chunk: stsc collapses to a single entry.
            ByteBuffer stsc = Boxes.stsc(Collections.nCopies(track.samples.size(), 1));
            ByteBuffer chunkOffsets = useCo64(track.offsets)
                    ? Boxes.co64(track.offsets) : Boxes.stco(track.offsets);

            ByteBuffer mhdBox;
            ByteBuffer sampleEntry;
            String handlerType;
            String handlerName;
            ByteBuffer stbl;
            if (audio) {
                handlerType = "soun";
                handlerName = "SoundHandle";
                mhdBox = Boxes.smhd();
                sampleEntry = Boxes.audioSampleEntry(format);
                stbl = Boxes.stbl(Boxes.stsd(sampleEntry), stts, stsz, stsc, chunkOffsets);
            } else {
                handlerType = "vide";
                handlerName = "VideoHandle";
                mhdBox = Boxes.vmhd();
                sampleEntry = Boxes.videoSampleEntry(format);
                ByteBuffer ctts = Boxes.ctts(track.samples, durationsVu, timescale);
                stbl = Boxes.stbl(Boxes.stsd(sampleEntry), stts, ctts, stsz, stsc, chunkOffsets,
                        Boxes.stss(track.samples));
            }
            int orientation = format.rotationDegrees != Format.NO_VALUE ? format.rotationDegrees : 0;
            ByteBuffer trak = Boxes.trak(
                    Boxes.tkhd(nextTrackId, presentationDurationUs, 0, 0, orientation, format),
                    Boxes.edts(firstPtsUs, minInputPtsUs, presentationDurationUs, MVHD_TIMESCALE,
                            timescale),
                    Boxes.mdia(
                            Boxes.mdhd(trackDurationVu, timescale, 0, 0, "und"),
                            Boxes.hdlr(handlerType, handlerName),
                            Boxes.minf(mhdBox, Boxes.dinf(Boxes.dref(Boxes.localUrl())), stbl)));
            trakBoxes.add(trak);
            maxPresentationDurationUs = Math.max(maxPresentationDurationUs, presentationDurationUs);
            nextTrackId++;
        }

        List<ByteBuffer> subBoxes = new ArrayList<>();
        subBoxes.add(Boxes.mvhd(nextTrackId, 0, 0, maxPresentationDurationUs));
        subBoxes.addAll(trakBoxes);
        return BoxUtils.wrapBoxesIntoBox("moov", subBoxes);
    }

    private static boolean useCo64(List<Long> offsets) {
        for (Long offset : offsets) if (offset > UINT32_MAX) return true;
        return false;
    }

    // ---------- low level writing ----------

    private void writeFlushed(ByteBuffer data) throws IOException {
        while (data.hasRemaining()) channel.write(data);
        flushed = channel.position();
    }

    private void flush() throws IOException {
        pending.flip();
        writeFlushed(pending);
        pending.clear();
    }
}
