/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.player.mixing.output;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author kernitus
 */
public final class WaveOutput implements SinkOutput {
    // TODO could not an HTTP or UDP stream of the audio data itself
    private static final Logger LOGGER = LoggerFactory.getLogger(WaveOutput.class);
    private final File file;
    private OutputStream stream;
    private int bytesPerSecond;
    private AtomicInteger bytesWrittenSinceLastWrite = new AtomicInteger(0);
    private AtomicLong lastWriteTimeStamp = new AtomicLong(0);

    public WaveOutput(@NotNull File file) {
        this.file = file;
    }

    private void ensureOutputFileExists() throws IOException {
        if (stream == null) {
            if (!file.exists()) {
                try {
                    Process p = new ProcessBuilder()
                            .command("mkfifo", file.getAbsolutePath())
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start();
                    p.waitFor();
                    if (p.exitValue() != 0)
                        LOGGER.warn("Failed creating pipe! {exit: {}}", p.exitValue());
                    else
                        LOGGER.info("Created pipe: " + file);
                } catch (InterruptedException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            stream = new BufferedOutputStream(Files.newOutputStream(file.toPath()), 3600); // write out 49 times/sec
        }
    }

    @Override
    public boolean start(@NotNull OutputAudioFormat format) throws SinkException {
        bytesPerSecond = (int) (format.getSampleRate() * format.getSampleSizeInBits() / 8);
        lastWriteTimeStamp.set(System.currentTimeMillis());
        LOGGER.info("Bytes per second: " + bytesPerSecond);
        try {
            ensureOutputFileExists();
            // Write out Wav file header
            //WavFile.writeHeader(stream, format.getChannels(), format.getSampleSizeInBits(), (long) format.getSampleRate());
        } catch (IOException e) {
            LOGGER.error("Failed to open WAV file for writing");
            throw new RuntimeException(e);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Illegal argument while opening WAV file");
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public void drain() {
        // Write existing buffer out
        try {
            stream.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    //@Override
    //public void stop() {
    //    // TODO on stop we should probably flush the buffer/file or nuke it cause it keeps playing what was already written
    //    // may not be that much of an issue if we call kodi's stop() or pause() on the player itself
    //}


    @Override
    public void write(byte[] buffer, int offset, int len) throws IOException {
        ensureOutputFileExists();
        // we have to rate limit the writing to the actual speed of playback otherwise we drain the whole thing and spotify has a fit
        final long currentTime = System.currentTimeMillis();
        final long msSinceLastWrite = currentTime - lastWriteTimeStamp.get();
        final int written = bytesWrittenSinceLastWrite.get();

        // If the bytes we want to write now + last written exceed rate, only write up to rate, then wait
        final int bytesThatCanBeWritten = Math.max(0, (int) ((msSinceLastWrite * bytesPerSecond * 1000) - written));

        //LOGGER.info("can be written: " + bytesThatCanBeWritten + " len: " + len);

        // Write the bytes that can be written
        /*
        if (len <= bytesThatCanBeWritten) {
            stream.write(buffer, offset, len);
            lastWriteTimeStamp.set(currentTime);
            bytesWrittenSinceLastWrite.set(len);
        } else {
            stream.write(buffer, offset, bytesThatCanBeWritten);
            // remaining bytes to write is len - bytesThatCanBeWritten
            final int remainingBytesToWrite = len - bytesThatCanBeWritten;
            final int msToWait = remainingBytesToWrite / (bytesPerSecond * 1000);
            try {
                Thread.sleep(msToWait);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // Write whatever was left after waiting
            stream.write(buffer, offset + bytesThatCanBeWritten, remainingBytesToWrite);
            lastWriteTimeStamp.set(currentTime + msToWait);
            bytesWrittenSinceLastWrite.set(len);
        }
         */
        stream.write(buffer, offset, len);
        try {
            Thread.sleep(22); // 23, 22 is better
            // TODO instead of waiting here, block for just under a second if we have written a second's worth of bytes
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
