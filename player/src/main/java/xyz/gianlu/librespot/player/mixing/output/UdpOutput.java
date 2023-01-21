package xyz.gianlu.librespot.player.mixing.output;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.player.mixing.output.wave.WavFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * @author kernitus
 */
public final class UdpOutput implements SinkOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger(UdpOutput.class);
    private final int port = 50005;
    private DatagramSocket socket;
    private InetAddress address;

    // raspivid  -hf -vf -t -0 -w 1080 -h 720 -awb auto -fps 15 -rot 90 -b 1200000 -o - |ffmpeg -loglevel quiet -i - -vcodec copy -an -r 15 -f rtp rtp://192.168.188.5:1234
    private void sendHeader(OutputAudioFormat format){
        try {
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(64);
            WavFile.writeHeader(outputStream, format.getChannels(), format.getSampleSizeInBits(), (long) format.getSampleRate());
            byte[] buffer = outputStream.toByteArray();
            final DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, port);
            socket.send(packet);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean start(@NotNull OutputAudioFormat format) throws SinkException {
        try {
            socket = new DatagramSocket();
            address = InetAddress.getByName("127.0.0.1");
            // Write out Wav file header
            sendHeader(format);
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
    public void write(byte[] buffer, int offset, int len) throws IOException {
        // we need to load this stuff into a global buffer and rate limit the output
        // otherwise we drain the whole thing at once!!
        // this needs to block until we can read more
        final DatagramPacket packet = new DatagramPacket(buffer, len, address, port);
        socket.send(packet);
        try {
            Thread.sleep(100); // wait 100ms
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
