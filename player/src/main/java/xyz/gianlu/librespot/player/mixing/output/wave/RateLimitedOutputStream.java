package xyz.gianlu.librespot.player.mixing.output.wave;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimitedOutputStream extends BufferedOutputStream {
    // e.g. 44100 sample rate, 16 bits per sample, 2 channels is 44100 * 16 * 2 = 1411200 b/s = 176400 B/s = 176.4 B/ms
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitedOutputStream.class);
    private final int bytesPerMillisecond;
    private final AtomicInteger tokens = new AtomicInteger(0);
    private final OutputStream stream;
    private final Timer tokenThread;

    public RateLimitedOutputStream(OutputStream stream, int bytesPerSecond) {
        super(stream, bytesPerSecond / 100); // TODO might want to specify size of underlying buffer
        this.stream = stream;
        this.bytesPerMillisecond = bytesPerSecond / 1000;
        tokenThread = new Timer();
        tokenThread.schedule(new TimerTask() {
            @Override
            public void run() {
                final int newValue = tokens.accumulateAndGet(bytesPerSecond / 100, Integer::sum);
                LOGGER.debug("New token value: " + newValue);
            }
        }, 0L, 10L);
        // consider scheduleAtFixedRate which keeps the rate constant over time
    }

    @Override
    public void write(byte @NotNull [] bytes) throws IOException {
        write(bytes, 0, bytes.length);
    }

    @Override
    public void write(byte @NotNull [] bytes, int offset, int length) throws IOException {
        int currentOffset = offset;
        while (currentOffset < length) {
            final int budget = tokens.get();
            final int toWrite = Math.min(length - currentOffset, budget);
            LOGGER.debug("Tokens: " + budget);
            LOGGER.debug("Writing " + toWrite + " bytes");
            stream.write(bytes, currentOffset, toWrite);
            stream.flush();
            tokens.accumulateAndGet(toWrite, (a, b) -> a - b);
            currentOffset += toWrite;

            // Wait to write the rest of the bytes
            final int remainingBytes = length - currentOffset;
            if(remainingBytes > 0) {
                final long msToWait = remainingBytes / bytesPerMillisecond;
                LOGGER.debug("Waiting " + msToWait + " to write remaining " + remainingBytes);
                try {
                    Thread.sleep(msToWait);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        tokenThread.cancel();
    }
}
