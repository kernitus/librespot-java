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
    private HttpServer server;
    private OutputAudioFormat format = OutputAudioFormat.DEFAULT_FORMAT;
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

        headerWritten = new CompletableFuture<>();

        // Open HTTP stream and write out necessary headers
        server.createContext("/", httpExchange -> {
            // If header already written, make new future because we'll be writing it again
            if (headerWritten.getNow(false))
                headerWritten = new CompletableFuture<>();

            LOGGER.info("Got a " + httpExchange.getRequestMethod() + " request");
            if(!httpExchange.getRequestMethod().equals("GET")) return;

            httpExchange.getRequestHeaders().forEach((h, l) -> LOGGER.info("Header: " + h + " value: " + l));
            int response = 200;
            boolean sendWaveHeader = true;

            if (httpExchange.getRequestHeaders().containsKey("Range")) {
                final String rangeString = httpExchange.getRequestHeaders().getFirst("Range");
                String[] rangeParts = rangeString.split("=");
                final String rangeUnit = rangeParts.length >= 2 ? rangeParts[0] : "";
                long rangeStart = 0, rangeEnd = 0;
                if(rangeParts.length >= 2){
                    rangeParts = rangeParts[1].split("-");
                    if(rangeParts.length >= 1)
                        rangeStart = Integer.parseInt(rangeParts[0]);
                    if(rangeParts.length >= 2)
                        rangeEnd = Integer.parseInt(rangeParts[1]);
                    else
                        // Send in chunks of 999 bytes if not requested otherwise, Kodi doesn't seem to use this anyway
                        rangeEnd = rangeStart + 999;
                }

                // Only send header at the start of a stream
                if (rangeStart != 0) sendWaveHeader = false;

                response = 206;
                // Sometimes it does ask for valid range, but we're streaming, so send back pretending it worked
                // Asterisk means we don't know full size
                // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Range
                httpExchange.getResponseHeaders().add("Content-Range", rangeUnit + " " + rangeStart + "-" + rangeEnd + "/*");
            }

            // Can use audio/l16 but it's just white noise - because it should be big endian
            // L16 format https://www.rfc-editor.org/rfc/rfc3551#page-27
            // https://www.rfc-editor.org/rfc/rfc2586
            // Kodi opens audio/l16 as pcms16be, but doesn't need wav header
            //httpExchange.getResponseHeaders().add("Content-Type", "audio/l16;rate=44100;channels=2");
            httpExchange.getResponseHeaders().add("Content-Type", "audio/wav");
            httpExchange.getResponseHeaders().add("Accept-Ranges", "bytes");

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
            //stream = new BufferedOutputStream(httpExchange.getResponseBody(), 4200); // 176400/4200 = 42
            LOGGER.info("Opened response body");
            if (sendWaveHeader && !headerWritten.getNow(false)) {
                WavFile.writeHeader(stream, format.getChannels(), format.getSampleSizeInBits(), (long) format.getSampleRate());
                LOGGER.info("Wrote WAV header");
            }
            // Let the write method proceed
            headerWritten.complete(true);
        });

        server.start();
    }

    @Override
    public boolean start(@NotNull OutputAudioFormat format) throws SinkException {
        // Everytime we start playback again, we must write the header
        headerWritten = new CompletableFuture<>();
        this.format = format;
        stopped = false;
        return true;
    }

    @Override
    public void write(byte[] buffer, int offset, int len) throws IOException {
        // Block until we have written the header
        try {
            if (!headerWritten.get()) return;
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
            LOGGER.info("Error writing to stream!");
            headerWritten = new CompletableFuture<>();
        }
    }

    @Override
    public void close() throws IOException {
        if (stream != null) stream.close();
        //if (server != null) server.stop(0);
        //server = null;
        headerWritten = null;
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
        headerWritten = new CompletableFuture<>();
        stopped = true;
        LOGGER.info("We paused");
    }
}