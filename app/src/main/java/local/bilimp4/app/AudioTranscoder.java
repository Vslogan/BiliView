package local.bilimp4.app;

import android.media.MediaCodec;
import android.media.MediaFormat;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;

import net.sourceforge.lame.lowlevel.LameEncoder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Converts a cached audio track into a standalone audio file: it decodes the track to
 * 16-bit PCM with MediaCodec and then either wraps it in a WAV container (lossless)
 * or encodes it as a 320 kbps MP3 using the vendored pure-Java LAME port. The M4A
 * path needs no decoding and stays in ExportTask.
 */
@OptIn(markerClass = UnstableApi.class)
final class AudioTranscoder {
    private static final int TIMEOUT_US = 10_000;
    private static final int MP3_QUALITY = 0; // LAME quality: 0 best/slowest … 9 worst；导出追求最高音质，不计耗时
    private static final int PCM_CHUNK = 64 * 1024;

    /** Receives raw little-endian 16-bit PCM; the format arrives before the first bytes. */
    private interface PcmSink {
        void format(int sampleRate, int channels) throws IOException;

        void write(byte[] data, int offset, int length) throws IOException;

        void finish() throws IOException;

        void close();
    }

    static void transcode(File source, File destination, ExportOptions.AudioFormat format,
                          int mp3BitrateKbps, ExportTask task) throws IOException {
        String stage = format == ExportOptions.AudioFormat.MP3 ? "正在转码 MP3" : "正在合成 WAV";
        task.progress(stage, -1);
        try (Mp4Track track = Mp4Track.open(source, C.TRACK_TYPE_AUDIO)) {
            Mp4Track.Sample sample = track.next();
            if (sample == null || track.format() == null) {
                throw new IOException("所选文件没有可导出的音轨。");
            }
            PcmSink sink = format == ExportOptions.AudioFormat.MP3
                    ? new Mp3Sink(destination, mp3BitrateKbps) : new WavSink(destination);
            try {
                decode(track, sample, sink, stage, task);
                sink.finish();
            } finally {
                sink.close();
            }
        }
        task.progress(stage, 100);
    }

    private static void decode(Mp4Track track, Mp4Track.Sample first, PcmSink sink, String stage,
                               ExportTask task) throws IOException {
        Format format = track.format();
        String mime = format.sampleMimeType;
        MediaCodec decoder;
        try {
            decoder = MediaCodec.createDecoderByType(mime);
            MediaFormat media = MediaFormat.createAudioFormat(mime,
                    format.sampleRate > 0 ? format.sampleRate : 44100,
                    format.channelCount > 0 ? format.channelCount : 2);
            if (!format.initializationData.isEmpty()) {
                media.setByteBuffer("csd-0", ByteBuffer.wrap(format.initializationData.get(0)));
            }
            decoder.configure(media, null, null, 0);
            decoder.start();
        } catch (IllegalArgumentException | IllegalStateException | IOException error) {
            throw new IOException("无法解码音轨（" + mime + "）。", error);
        }
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] chunk = new byte[PCM_CHUNK];
            Mp4Track.Sample pending = first;
            boolean inputDone = false;
            boolean outputDone = false;
            boolean formatSent = false;
            while (!outputDone) {
                task.check();
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        if (buffer == null) throw new IOException("解码器输入不可用。");
                        if (pending == null) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            byte[] data = pending.data();
                            buffer.clear();
                            buffer.put(data);
                            decoder.queueInputBuffer(index, 0, data.length, Math.max(0, pending.timeUs()), 0);
                            task.progress(stage, ExportTask.percent(pending.timeUs(), track.durationUs(), 99));
                            pending = track.next();
                        }
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat changed = decoder.getOutputFormat();
                    int pcm = changed.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? changed.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            : android.media.AudioFormat.ENCODING_PCM_16BIT;
                    if (pcm != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                        throw new IOException("解码输出的 PCM 格式不受支持。");
                    }
                    sink.format(changed.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    formatSent = true;
                } else if (index >= 0) {
                    if (!formatSent) {
                        sink.format(format.sampleRate > 0 ? format.sampleRate : 44100,
                                format.channelCount > 0 ? format.channelCount : 2);
                        formatSent = true;
                    }
                    ByteBuffer buffer = decoder.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        int remaining = info.size;
                        while (remaining > 0) {
                            int count = Math.min(remaining, chunk.length);
                            buffer.get(chunk, 0, count);
                            sink.write(chunk, 0, count);
                            remaining -= count;
                        }
                    }
                    decoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        } catch (IllegalStateException error) {
            throw new IOException("音轨解码失败。", error);
        } finally {
            try {
                decoder.stop();
            } catch (IllegalStateException ignored) {
                // The codec is already released below.
            }
            decoder.release();
        }
    }

    /** Streams PCM into a RIFF/WAVE file, patching the header sizes at the end. */
    private static final class WavSink implements PcmSink {
        private final RandomAccessFile file;
        private long dataBytes;
        private boolean headerWritten;
        private boolean closed;

        WavSink(File destination) throws IOException {
            file = new RandomAccessFile(destination, "rw");
            file.setLength(0);
        }

        @Override public void format(int sampleRate, int channels) throws IOException {
            if (headerWritten) return;
            headerWritten = true;
            file.seek(0);
            file.write(header(sampleRate, channels, 0));
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            file.write(data, offset, length);
            dataBytes += length;
            if (dataBytes > 0xFFFF_FF00L) throw new IOException("WAV 文件过大，请选择 MP3 或 M4A。");
        }

        @Override public void finish() throws IOException {
            if (!headerWritten) throw new IOException("音轨没有可导出的音频数据。");
            long total = dataBytes;
            file.seek(0);
            byte[] header = new byte[44];
            file.readFully(header);
            writeInt(header, 4, (int) (36 + total));
            writeInt(header, 40, (int) total);
            file.seek(0);
            file.write(header);
            file.getFD().sync();
            close();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                file.close();
            } catch (IOException ignored) {
                // Nothing else to release.
            }
        }

        private static byte[] header(int sampleRate, int channels, long dataBytes) {
            byte[] header = new byte[44];
            byte[] riff = {'R', 'I', 'F', 'F'};
            byte[] wave = {'W', 'A', 'V', 'E'};
            byte[] fmt = {'f', 'm', 't', ' '};
            byte[] data = {'d', 'a', 't', 'a'};
            System.arraycopy(riff, 0, header, 0, 4);
            writeInt(header, 4, (int) (36 + dataBytes));
            System.arraycopy(wave, 0, header, 8, 4);
            System.arraycopy(fmt, 0, header, 12, 4);
            writeInt(header, 16, 16);                     // PCM chunk size
            writeShort(header, 20, 1);                    // PCM format
            writeShort(header, 22, channels);
            writeInt(header, 24, sampleRate);
            writeInt(header, 28, sampleRate * channels * 2);
            writeShort(header, 32, channels * 2);
            writeShort(header, 34, 16);                   // bits per sample
            System.arraycopy(data, 0, header, 36, 4);
            writeInt(header, 40, (int) dataBytes);
            return header;
        }

        private static void writeInt(byte[] target, int offset, int value) {
            target[offset] = (byte) value;
            target[offset + 1] = (byte) (value >>> 8);
            target[offset + 2] = (byte) (value >>> 16);
            target[offset + 3] = (byte) (value >>> 24);
        }

        private static void writeShort(byte[] target, int offset, int value) {
            target[offset] = (byte) value;
            target[offset + 1] = (byte) (value >>> 8);
        }
    }

    /** Feeds PCM into the vendored LAME encoder and writes 320 kbps MP3 frames. */
    private static final class Mp3Sink implements PcmSink {
        private final RandomAccessFile file;
        private final int bitrateKbps;
        private LameEncoder encoder;
        private int channels;
        private byte[] pending = new byte[0];
        private byte[] encoded;
        private boolean closed;

        Mp3Sink(File destination, int bitrateKbps) throws IOException {
            file = new RandomAccessFile(destination, "rw");
            file.setLength(0);
            this.bitrateKbps = bitrateKbps > 0 ? bitrateKbps : 320;
        }

        @Override public void format(int sampleRate, int channels) throws IOException {
            if (encoder != null) return;
            if (channels > 2) throw new IOException("多声道音轨无法转为 MP3，请选择 WAV 或 M4A。");
            this.channels = channels;
            try {
                encoder = new LameEncoder(channels, sampleRate, bitrateKbps, MP3_QUALITY);
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new IOException("该音轨的参数无法转为 MP3，请选择 WAV 或 M4A。", error);
            }
            encoded = new byte[encoder.getMP3BufferSize()];
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            byte[] input = data;
            if (pending.length > 0) {
                input = Arrays.copyOf(pending, pending.length + length);
                System.arraycopy(data, offset, input, pending.length, length);
                offset = 0;
                length = input.length;
                pending = new byte[0];
            }
            int sampleBytes = 2 * Math.max(1, channels);
            int chunk = encoder.getPCMBufferSize();
            chunk -= chunk % sampleBytes;
            int whole = length - length % sampleBytes;
            int position = offset;
            int end = offset + whole;
            while (position < end) {
                int count = Math.min(chunk, end - position);
                flush(input, position, count);
                position += count;
            }
            int rest = offset + length - position;
            if (rest > 0) pending = Arrays.copyOfRange(input, position, offset + length);
        }

        private void flush(byte[] input, int offset, int length) throws IOException {
            int written = encoder.encodeBuffer(input, offset, length, encoded);
            if (written > 0) file.write(encoded, 0, written);
        }

        @Override public void finish() throws IOException {
            if (encoder == null) throw new IOException("音轨没有可导出的音频数据。");
            if (pending.length > 0) flush(pending, 0, pending.length);
            int written = encoder.encodeFinish(encoded);
            if (written > 0) file.write(encoded, 0, written);
            file.getFD().sync();
            close();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                file.close();
            } catch (IOException ignored) {
                // Nothing else to release.
            }
        }
    }
}
