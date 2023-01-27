package xyz.gianlu.librespot.player.mixing.output;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.player.mixing.output.wave.RateLimitedOutputStream;
import xyz.gianlu.librespot.player.mixing.output.wave.WavFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * @author kernitus
 */
public final class HttpOutput implements SinkOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpOutput.class);
    private final int PORT = 50001; // TODO don't hardcode port
    private final String HOST = "127.0.0.1"; // TODO don't hardcode host
    private OutputStream stream;
    private CompletableFuture<Boolean> headerWritten;
    HttpServer server;

    @Override
    public boolean start(@NotNull OutputAudioFormat format) throws SinkException {
        if(server != null){
            LOGGER.info("Server is not null, not recreating one!");
            return true;
        }
        headerWritten = new CompletableFuture<>();
        try {
            // Create server only allowing one connection at a time
            server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
            LOGGER.info("Started HTTP server on " + HOST + ":" + PORT);

            // Open HTTP stream and write out necessary headers
            server.createContext("/", httpExchange -> {
                LOGGER.info("Got a " + httpExchange.getRequestMethod() + " request");
                httpExchange.getRequestHeaders().forEach((h, l) -> LOGGER.info("Header: " + h + " value: " + l));
                int response = 200;
                if (httpExchange.getRequestHeaders().containsKey("Range")) {
                    final String rangeString = httpExchange.getRequestHeaders().getFirst("Range");
                    response = 206;
                    // Sometimes it does ask for valid range, but we're streaming, so send back pretending it worked
                    // Asterisk means we don't know full size
                    httpExchange.getResponseHeaders().add("Content-Range", "bytes " + rangeString + "/*");
                }

                httpExchange.getResponseHeaders().add("Content-Type", "audio/wav");
                httpExchange.getResponseHeaders().add("Accept-Ranges", "bytes");

                // No response body should be returned if it's a HEAD request
                if (httpExchange.getRequestMethod().equals("HEAD")) {
                    httpExchange.sendResponseHeaders(response, -1);
                    return;
                }

                // Length 0 means chunked transfer, we keep going until output stream is closed
                httpExchange.sendResponseHeaders(response, 0);
                LOGGER.info("Sent response headers");

                final int byteRate = (int) (format.getChannels() * format.getSampleSizeInBits() * format.getSampleRate() / 8);
                LOGGER.info("Byte rate: " + byteRate);
                stream = new RateLimitedOutputStream(httpExchange.getResponseBody(), byteRate);
                //stream = new BufferedOutputStream(httpExchange.getResponseBody(), 4200); // 176400/4200 = 42
                LOGGER.info("Opened response body");
                WavFile.writeHeader(stream, format.getChannels(), format.getSampleSizeInBits(), (long) format.getSampleRate());
                LOGGER.info("Wrote WAV header");
                // Let the write method proceed
                headerWritten.complete(true);
            });

            server.start();

        } catch (UnknownHostException e) {
            LOGGER.error("Unknown host " + HOST);
            headerWritten.complete(false);
        } catch (IOException e) {
            LOGGER.error("Couldn't get I/O for the connection to " + HOST);
            headerWritten.complete(false);
        }

        return true;
    }

    @Override
    public void write(byte[] buffer, int offset, int len) throws IOException {
        // Block until we have written the header
        try {
            if(!headerWritten.get()) return;
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to acquire audio output write lock");
            throw new RuntimeException(e);
        }

        // Write to body stream that was opened in start()
        stream.write(buffer);

        // TODO find better way to sync playback
        // as the playing keeps going, kodi falls behind what spotify reports as the playback
        // is librespot's calculations for playback tied to how quickly we write out of the buffer?
        /*
        try {
            // This is around ~23 that with 4096 byte buffer is slightly faster than s16le 44100 2 channel playback data rate
            // Necessary otherwise we buffer too much into HTTP stream and pausing / skipping no longer match up
            Thread.sleep(22);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
         */
    }

    @Override
    public void close() throws IOException {
        if(stream != null) stream.close();
        if(server != null) server.stop(0);
        server = null;
        headerWritten = null;
        LOGGER.info("HTTP stream has been closed");
    }
}
