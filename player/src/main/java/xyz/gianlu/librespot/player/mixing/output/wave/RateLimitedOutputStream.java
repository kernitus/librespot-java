package xyz.gianlu.librespot.player.mixing.output.wave;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;

public class RateLimitedOutputStream extends OutputStream {
    // e.g. 44100 sample rate, 16 bits per sample, 2 channels is 44100 * 16 * 2 = 1411200 b/s = 176400 B/s = 176.4 B/ms
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitedOutputStream.class);
    private final double bytesPerMillisecond;
    private long startTime, bytesWritten;
    private final OutputStream stream;

    public RateLimitedOutputStream(OutputStream stream, int bytesPerSecond) {
        this.stream = stream;
        this.bytesPerMillisecond = ((double) bytesPerSecond) / 1000.0;
        startTime = System.currentTimeMillis();
        bytesWritten = -44; // To account for header being 44 bytes
    }

    @Override
    public void write(int i) throws IOException {
        stream.write(i);
    }

    @Override
    public void write(byte @NotNull [] bytes) throws IOException {
        write(bytes, 0, bytes.length);
    }

    @Override
    public void write(byte @NotNull [] bytes, int offset, int length) throws IOException {
        // Calculate the max amount of bytes we could have written
        final long currentTime = System.currentTimeMillis();
        final long elapsedTime = currentTime - startTime;
        final long maxWrittenBytes = (long) (elapsedTime * bytesPerMillisecond);

        // Write first few bytes
        final long byteBudget = Math.max(0, (maxWrittenBytes - bytesWritten));
        final int toWrite = (int) Math.min(length, byteBudget);
        //LOGGER.debug("Writing " + toWrite + " bytes");
        stream.write(bytes, offset, toWrite);
        // TODO deal with this variable overflowing
        bytesWritten += toWrite;

        if (length > byteBudget) {
            // Wait to write the rest
            final int remainingBytes = length - (int) byteBudget;
            //LOGGER.debug("Remaining bytes: " + remainingBytes + " length: " + length + " budget:" + byteBudget);
            final long msToWait = (long) (remainingBytes / bytesPerMillisecond);
            if (msToWait > 0) {
                try {
                    //LOGGER.debug("Waiting " + msToWait + " to write remaining " + remainingBytes);
                    Thread.sleep(msToWait);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            //LOGGER.debug("Writing remaining " + remainingBytes + " bytes");
            stream.write(bytes, toWrite, remainingBytes);
            bytesWritten += remainingBytes;
        }
    }

    @Override
    public void close() throws IOException {
       stream.close();
    }
}
