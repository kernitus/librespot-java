package xyz.gianlu.librespot.player.mixing.output.wave;

// Wav file IO class
// A.Greensted
// From http://www.labbookpages.co.uk/audio/javaWavFiles.html

// File format is based on the information from
// http://www.sonicspot.com/guide/wavefiles.html
// http://www.blitter.com/~russtopia/MIDI/~jglatt/tech/wave.htm

// Heavily modified by kernitus to just write out file header

import java.io.IOException;
import java.io.OutputStream;

public class WavFile {

    private WavFile(){}

    private final static int BUFFER_SIZE = 4096;

    private final static int FMT_CHUNK_ID = 0x20746D66;
    private final static int DATA_CHUNK_ID = 0x61746164;
    private final static int RIFF_CHUNK_ID = 0x46464952;
    private final static int RIFF_TYPE_ID = 0x45564157;

    /**
     * Creates WAV file and writes out header.
     * @param outputStream The stream to write to. Usually a file.
     * @param numChannels The number of channels in the PCM stream.
     * @param bitsPerSample The number of bits per sample in the PCM stream, e.g. 16 or 32.
     * @param sampleRate The number of samples per second, e.g. 44100.
     * @throws IOException Could not open the stream for writing.
     * @throws IllegalArgumentException One of the arguments was not within bounds.
     */
    public static void writeHeader(OutputStream outputStream, int numChannels, int bitsPerSample, long sampleRate) throws IOException, IllegalArgumentException {
        // Local buffer used for IO
        byte[] buffer = new byte[BUFFER_SIZE];

        // Number of bytes required to store a single sample
        int bytesPerSample = (bitsPerSample + 7) / 8;
        // Wav Header
        //private int numChannels;                // 2 bytes unsigned, 0x0001 (1) to 0xFFFF (65,535)
        //private long sampleRate;                // 4 bytes unsigned, 0x00000001 (1) to 0xFFFFFFFF (4,294,967,295)
        // Although a java int is 4 bytes, it is signed, so need to use a long
        // 2 bytes unsigned, 0x0001 (1) to 0xFFFF (65,535)
        int blockAlign = bytesPerSample * numChannels;

        // Sanity checks
        if (numChannels < 1 || numChannels > 65535)
            throw new IllegalArgumentException("Illegal number of channels, valid range 1 to 65536");
        if (bitsPerSample < 2 || bitsPerSample > 65535)
            throw new IllegalArgumentException("Illegal number of valid bits, valid range 2 to 65536");
        if (sampleRate < 0) throw new IllegalArgumentException("Sample rate must be positive");

        // Calculate the chunk sizes
        //long dataChunkSize = blockAlign * numFrames;
        //long mainChunkSize = 4 +    // Riff Type
        //        8 +    // Format ID and size
        //        16 +    // Format data
        //        8 +    // Data ID and size
        //        dataChunkSize;

        // Set to maximum unsigned value to allow streaming the file
        // TODO these are actually unsigned ints, so max file size will be 4GB
        // we may want to use 64bit headers to support huge files and not have it stop
        long mainChunkSize = -1; // -8
        long dataChunkSize = -44; // -44

        // Chunks must be word aligned, so if odd number of audio data bytes
        // adjust the main chunk size
        //if (dataChunkSize % 2 == 1) {
        //    mainChunkSize += 1;
        //    wordAlignAdjust = true;
        //} else {
        //    wordAlignAdjust = false;
        //}

        // Set the main chunk size
        putLE(RIFF_CHUNK_ID, buffer, 0, 4);
        putLE(mainChunkSize, buffer, 4, 4);
        putLE(RIFF_TYPE_ID, buffer, 8, 4);

        // Write out the header
        outputStream.write(buffer, 0, 12);

        // Put format data in buffer
        long averageBytesPerSecond = sampleRate * blockAlign;

        putLE(FMT_CHUNK_ID, buffer, 0, 4);        // Chunk ID
        putLE(16, buffer, 4, 4);        // Chunk Data Size
        putLE(1, buffer, 8, 2);        // Compression Code (Uncompressed)
        putLE(numChannels, buffer, 10, 2);        // Number of channels
        putLE(sampleRate, buffer, 12, 4);        // Sample Rate
        putLE(averageBytesPerSecond, buffer, 16, 4);        // Average Bytes Per Second
        putLE(blockAlign, buffer, 20, 2);        // Block Align
        putLE(bitsPerSample, buffer, 22, 2);        // Valid Bits

        // Write Format Chunk
        outputStream.write(buffer, 0, 24);

        // Start Data Chunk
        putLE(DATA_CHUNK_ID, buffer, 0, 4);        // Chunk ID
        putLE(dataChunkSize, buffer, 4, 4);        // Chunk Data Size

        // Write Format Chunk
        outputStream.write(buffer, 0, 8);

        // Make sure the header etc. is fully written before we try to write any actual data
        outputStream.flush();
    }

    // Get and Put little endian data from local buffer
    // ------------------------------------------------
    private static void putLE(long val, byte[] buffer, int pos, int numBytes) {
        for (int b = 0; b < numBytes; b++) {
            buffer[pos] = (byte) (val & 0xFF);
            val >>= 8;
            pos++;
        }
    }
}
