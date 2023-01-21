package xyz.gianlu.librespot.player.mixing.output;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.player.mixing.output.wave.WavFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author kernitus
 */
public final class HttpOutput implements SinkOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpOutput.class);
    private final int PORT = 50001; // TODO don't hardcode port
    private final String HOST = "127.0.0.1"; // TODO don't hardcode host
    private OutputStream stream;
    HttpServer server;
    AtomicBoolean wroteHeader = new AtomicBoolean(false);

    @Override
    public boolean start(@NotNull OutputAudioFormat format) throws SinkException {
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

                stream = httpExchange.getResponseBody();
                LOGGER.info("Opened response body");
                WavFile.writeHeader(stream, format.getChannels(), format.getSampleSizeInBits(), (long) format.getSampleRate());
                LOGGER.info("Wrote WAV header");
                wroteHeader.set(true);
            });

            server.start();

        } catch (UnknownHostException e) {
            LOGGER.error("Don't know about host " + HOST);
        } catch (IOException e) {
            LOGGER.error("Couldn't get I/O for the connection to " + HOST);
            e.printStackTrace();
        }

        return true;
    }

    @Override
    public void write(byte[] buffer, int offset, int len) throws IOException {
        // Block until we have written the header
        // TODO see if we can get rid of the busy waiting
        while (!wroteHeader.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        // Write to body stream that was opened in start()
        stream.write(buffer, offset, len);
        try {
            Thread.sleep(22);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        // TODO might have to close() httpexchange or consume requestbody
        stream.close();
        server.stop(0);
    }
}
