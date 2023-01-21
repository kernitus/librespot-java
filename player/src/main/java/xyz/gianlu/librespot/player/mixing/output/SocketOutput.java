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
public final class SocketOutput implements SinkOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger(SocketOutput.class);
    private final int PORT = 50001;
    private final String HOST = "127.0.0.1";
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
                if(httpExchange.getRequestHeaders().containsKey("Range")){
                    final String rangeString = httpExchange.getRequestHeaders().getFirst("Range");
                    final String[] rangeValues = rangeString.replace("bytes=", "").split("-");
                    //if(rangeValues.length > 1)
                    response = 206;
                    // TODO sometimes it does ask for valid range, but we're streaming, so send back pretending it worked
                    httpExchange.getResponseHeaders().add("Content-Range", "bytes " + rangeString + "/*"); // * because we don't know full size

                }
                // Range value: [bytes=0-]

                // TODO seek through file based on Range header with getRequestHeaders()
                httpExchange.getResponseHeaders().add("Content-Type", "audio/wav");
                httpExchange.getResponseHeaders().add("Accept-Ranges", "bytes");
                httpExchange.sendResponseHeaders(response, 0); // Length 0 means chunked transfer, we keep going until output stream is closed

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
        // Write to body stream that was opened in start()
        while(!wroteHeader.get()){
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // Block until stream is open
        }
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
