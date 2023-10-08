package xyz.gianlu.librespot.player.mixing.output;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.player.mixing.output.wave.RateLimitedOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
    private CompletableFuture<Boolean> playingStarted;
    private HttpServer server;
    private OutputAudioFormat format = new OutputAudioFormat(44100, 16, 2, true, true);
    private boolean stopped = false;

    public HttpOutput() {
        // Start the server once, upon creation. Allow only one connection at a time
        try {
            server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
        } catch (IOException e) {
            LOGGER.error("Couldn't get I/O for the connection to " + HOST);
            throw new RuntimeException(e);
        }
        LOGGER.info("Started HTTP server on " + HOST + ":" + PORT);

        playingStarted = new CompletableFuture<>();

        // Open HTTP stream and write out necessary headers
        server.createContext("/", httpExchange -> {

            final String method = httpExchange.getRequestMethod();
            LOGGER.info("Got a " + method + " request");
            if(!method.equals("GET") && !method.equals("HEAD") ) return;

            // If playback already started in a previous request reset the future
            if (playingStarted.getNow(false) && method.equals("GET"))
                playingStarted = new CompletableFuture<>();

            httpExchange.getRequestHeaders().forEach((h, l) -> LOGGER.info("Header: " + h + " value: " + l));
            int response = 200;

            // Kodi opens audio/l16 as pcms16be, but doesn't need wav header
            // L16 format https://www.rfc-editor.org/rfc/rfc3551#page-27
            // https://www.rfc-editor.org/rfc/rfc2586
            httpExchange.getResponseHeaders().add("Content-Type", "audio/l16;rate=44100;channels=2");
            httpExchange.getResponseHeaders().add("Accept-Ranges", "none");

            // No response body should be returned if it's a HEAD request
            if (httpExchange.getRequestMethod().equals("HEAD")) {
                httpExchange.sendResponseHeaders(response, -1);
                return;
            }

            if(stopped){
                LOGGER.info("Paused, sending 416");
                httpExchange.sendResponseHeaders(416, 0);
                return;
            }

            // Length 0 means chunked transfer, we keep going until output stream is closed
            httpExchange.sendResponseHeaders(response, 0);
            LOGGER.info("Sent response headers");

            final int byteRate = (int) (format.getChannels() * format.getSampleSizeInBits() * format.getSampleRate() / 8);
            LOGGER.info("Byte rate: " + byteRate);
            stream = new RateLimitedOutputStream(httpExchange.getResponseBody(), byteRate);
            LOGGER.info("Opened response body");
            // Let the write method proceed
            playingStarted.complete(true);
        });

        server.start();
    }

    @Override
    public boolean start(@NotNull OutputAudioFormat format) throws SinkException {
        // Everytime we start playback again, reset the future
        playingStarted = new CompletableFuture<>();
        this.format = format;
        stopped = false;
        return true;
    }

    @Override
    public void write(byte[] buffer, int offset, int len) throws IOException {
        // Block until we have an output stream from the HTTP request
        try {
            if (!playingStarted.get()) return;
        } catch(InterruptedException e){
            LOGGER.info("Interrupted while waiting for header to be written");
        } catch (ExecutionException e) {
            LOGGER.error("Failed to acquire audio output write lock");
            throw new RuntimeException(e);
        }

        stopped = false;

        // Write to body stream that was opened when connection started
        try {
            stream.write(buffer);
        } catch (IOException e) {
            LOGGER.warn("Error writing to stream!");
            LOGGER.warn(e.getMessage());
            playingStarted = new CompletableFuture<>();
        }
    }

    @Override
    public void close() throws IOException {
        if (stream != null) stream.close();
        //if (server != null) server.stop(0);
        //server = null;
        playingStarted = null;
        LOGGER.info("HTTP stream has been closed");
    }

    @Override
    public void stop() {
        // Stop is called when playback is paused
        // Close output stream to terminate current connection
        if (stream != null) {
            try {
                stream.close();
                LOGGER.info("Closed output stream");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        // Stream is closed, so we will have to write header again
        playingStarted = new CompletableFuture<>();
        stopped = true;
        LOGGER.info("We paused");
    }
}