/*
 * Java port of LAME 3.98.4 (jump3r / java-lame), licensed under the LGPL v3.
 * See licenses/java-lame-LGPLv3.txt in the project root.
 *
 * Modified for Android: the javax.sound.sampled.AudioFormat based API was
 * replaced by plain integer parameters, the system-property lookup was
 * removed, and the PCM input is fixed to signed 16-bit little-endian
 * (the format produced by Android's MediaCodec decoders).
 */
package net.sourceforge.lame.lowlevel;

import net.sourceforge.lame.mp3.Lame;
import net.sourceforge.lame.mp3.MPEGMode;
import net.sourceforge.lame.mp3.VbrMode;
import net.sourceforge.lame.mp3.Version;

import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Wrapper for the jump3r encoder.
 *
 * @author Ken Händel
 */
public final class LameEncoder {
    /**
     * Constant bit rate.
     */
    public static final int BITRATE_AUTO = -1;

    /**
     * suggested maximum buffer size for an mpeg frame
     */
    private static final int DEFAULT_PCM_BUFFER_SIZE = 2048 * 16;

    /**
     * MP3 encoder.
     */
    private final Lame lame = new Lame();
    private final Version version = new Version();
    // encoding values: signed 16-bit little-endian PCM, as produced by MediaCodec
    private final int sampleSizeInBits = 16;
    private final ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;

    // these fields are set upon successful initialization to show effective
    // values.
    private int effBitRate;
    private int effSampleRate;

    /**
     * Initializes the encoder for signed 16-bit little-endian PCM input.
     *
     * @param channels    number of input channels, 1 or 2
     * @param sampleRate  input sample rate in Hz
     * @param bitRateKbps constant bit rate in kbit/s, 32…320, or BITRATE_AUTO
     * @param quality     1 (highest) to 9 (lowest)
     * @throws IllegalArgumentException when parameters are not supported by LAME.
     */
    public LameEncoder(int channels, int sampleRate, int bitRateKbps, int quality) {
        // simple check that bitrate is not too high for MPEG2 and MPEG2.5
        if (sampleRate < 32000 && bitRateKbps > 160) {
            bitRateKbps = 160;
        }
        lame.getFlags().setInNumChannels(channels);
        lame.getFlags().setInSampleRate(sampleRate);
        lame.getFlags().setMode(channels == 1 ? MPEGMode.MONO : MPEGMode.STEREO);
        lame.getFlags().setVBR(VbrMode.vbr_off);
        if (bitRateKbps != BITRATE_AUTO) {
            lame.getFlags().setBitRate(bitRateKbps);
        }
        lame.getFlags().setQuality(quality);
        lame.getId3().init(lame.getFlags());
        lame.getFlags().setWriteId3tagAutomatic(false);
        lame.getFlags().setFindReplayGain(true);
        int result = lame.initParams();
        effSampleRate = lame.getFlags().getOutSampleRate();
        effBitRate = lame.getFlags().getBitRate();
        if (result < 0) {
            throw new IllegalArgumentException(
                    "parameters not supported by LAME (returned " + result + ")");
        }
    }

    /**
     * VBR constructor; the boolean parameter only serves to distinguish this
     * signature from the CBR constructor.
     */
    private LameEncoder(int channels, int sampleRate, int vbrQuality, boolean vbr) {
        lame.getFlags().setInNumChannels(channels);
        lame.getFlags().setInSampleRate(sampleRate);
        lame.getFlags().setMode(channels == 1 ? MPEGMode.MONO : MPEGMode.STEREO);
        lame.getFlags().setVBR(VbrMode.vbr_mtrh);
        lame.getFlags().setVBRQuality(vbrQuality);
        lame.getFlags().setQuality(0); // psychoacoustic model refinement: best
        lame.getId3().init(lame.getFlags());
        lame.getFlags().setWriteId3tagAutomatic(false);
        lame.getFlags().setFindReplayGain(true);
        int result = lame.initParams();
        effSampleRate = lame.getFlags().getOutSampleRate();
        effBitRate = lame.getFlags().getBitRate();
        if (result < 0) {
            throw new IllegalArgumentException(
                    "parameters not supported by LAME (returned " + result + ")");
        }
    }

    /**
     * Creates a variable bit rate encoder. VBR lets the encoder spend bits only
     * where the audio needs them: no wasted space on simple passages, no
     * shortage on complex ones.
     *
     * @param channels    number of input channels, 1 or 2
     * @param sampleRate  input sample rate in Hz
     * @param vbrQuality  VBR level: 0 (highest quality, ~245 kbit/s average)
     *                    … 9 (smallest, ~65 kbit/s average)
     * @throws IllegalArgumentException when parameters are not supported by LAME.
     */
    public static LameEncoder vbr(int channels, int sampleRate, int vbrQuality) {
        return new LameEncoder(channels, sampleRate, vbrQuality, true);
    }

    /**
     * Get encoder version string.
     *
     * @return encoder version string
     */
    public String getEncoderVersion() {
        return version.getLameVersion();
    }

    public int getPCMBufferSize() {
        return DEFAULT_PCM_BUFFER_SIZE;
    }

    public int getMP3BufferSize() {
        // bad estimate :)
        return getPCMBufferSize() / 2 + 1024;
    }

    private int doEncodeBuffer(final byte[] pcm, final int pcmOffset,
                               final int length, final byte[] encoded) {
        int bytesPerSample = sampleSizeInBits >> 3;
        int samplesRead = length / bytesPerSample;
        int[] sampleBuffer = new int[samplesRead];

        int sampleBufferPos = samplesRead;
        if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            if (bytesPerSample == 1)
                for (int i = samplesRead * bytesPerSample; (i -= bytesPerSample) >= 0; )
                    sampleBuffer[--sampleBufferPos] = (pcm[pcmOffset + i] & 0xff) << 24;
            if (bytesPerSample == 2)
                for (int i = samplesRead * bytesPerSample; (i -= bytesPerSample) >= 0; )
                    sampleBuffer[--sampleBufferPos] = (pcm[pcmOffset + i] & 0xff) << 16
                            | (pcm[pcmOffset + i + 1] & 0xff) << 24;
            if (bytesPerSample == 3)
                for (int i = samplesRead * bytesPerSample; (i -= bytesPerSample) >= 0; )
                    sampleBuffer[--sampleBufferPos] = (pcm[pcmOffset + i] & 0xff) << 8
                            | (pcm[pcmOffset + i + 1] & 0xff) << 16
                            | (pcm[pcmOffset + i + 2] & 0xff) << 24;
            if (bytesPerSample == 4)
                for (int i = samplesRead * bytesPerSample; (i -= bytesPerSample) >= 0; )
                    sampleBuffer[--sampleBufferPos] = (pcm[pcmOffset + i] & 0xff)
                            | (pcm[pcmOffset + i + 1] & 0xff) << 8
                            | (pcm[pcmOffset + i + 2] & 0xff) << 16
                            | (pcm[pcmOffset + i + 3] & 0xff) << 24;
        } else {
            if (bytesPerSample == 1)
                for (int i = samplesRead * bytesPerSample; (i -= bytesPerSample) >= 0; )
                    sampleBuffer[--sampleBufferPos] = ((pcm[pcmOffset + i] & 0xff) ^ 0x80) << 24
                            | 0x7f << 16;
            if (bytesPerSample == 2)
                for (int i = samplesRead * bytesPerSample; (i -= bytesPerSample) >= 0; )
                    sampleBuffer[--sampleBufferPos] = (pcm[pcmOffset + i] & 0xff) << 24
                            | (pcm[pcmOffset + i + 1] & 0xff) << 16;
            if (bytesPerSample == 3)
                for (int i = samplesRead * bytesPerSample; (i -= bytesPerSample) >= 0; )
                    sampleBuffer[--sampleBufferPos] = (pcm[pcmOffset + i] & 0xff) << 24
                            | (pcm[pcmOffset + i + 1] & 0xff) << 16
                            | (pcm[pcmOffset + i + 2] & 0xff) << 8;
            if (bytesPerSample == 4)
                for (int i = samplesRead * bytesPerSample; (i -= bytesPerSample) >= 0; )
                    sampleBuffer[--sampleBufferPos] = (pcm[pcmOffset + i] & 0xff) << 24
                            | (pcm[pcmOffset + i + 1] & 0xff) << 16
                            | (pcm[pcmOffset + i + 2] & 0xff) << 8
                            | (pcm[pcmOffset + i + 3] & 0xff);
        }

        sampleBufferPos = samplesRead;
        samplesRead /= lame.getFlags().getInNumChannels();

        float buffer[][] = new float[2][samplesRead];
        if (lame.getFlags().getInNumChannels() == 2) {
            for (int i = samplesRead; --i >= 0; ) {
                buffer[1][i] = sampleBuffer[--sampleBufferPos];
                buffer[0][i] = sampleBuffer[--sampleBufferPos];
            }
        } else if (lame.getFlags().getInNumChannels() == 1) {
            Arrays.fill(buffer[1], 0, samplesRead, 0);
            for (int i = samplesRead; --i >= 0; ) {
                buffer[0][i] = buffer[1][i] = sampleBuffer[--sampleBufferPos];
            }
        }
        return lame.encodeBuffer(buffer[0], buffer[1], samplesRead, encoded);
    }

    /**
     * Encode a block of data. Throws IllegalArgumentException when parameters
     * are wrong. When the <code>encoded</code> array is too small, an
     * ArrayIndexOutOfBoundsException is thrown.
     *
     * @return the number of bytes written to <code>encoded</code>. May be 0.
     */
    public int encodeBuffer(final byte[] pcm, final int pcmOffset,
                            final int pcmLength, final byte[] encoded)
            throws ArrayIndexOutOfBoundsException {
        if (pcmLength < 0 || (pcmOffset + pcmLength) > pcm.length) {
            throw new IllegalArgumentException("inconsistent parameters");
        }
        int result = doEncodeBuffer(pcm, pcmOffset, pcmLength, encoded);
        if (result < 0) {
            if (result == -1) {
                throw new ArrayIndexOutOfBoundsException(
                        "Encode buffer too small");
            }
            throw new RuntimeException("crucial error in encodeBuffer.");
        }
        return result;
    }

    public int encodeFinish(final byte[] encoded) {
        return lame.encodeFlush(encoded);
    }

    public void close() {
        lame.close();
    }

    public int getEffectiveBitRate() {
        return effBitRate;
    }

    public int getEffectiveSampleRate() {
        return effSampleRate;
    }
}
