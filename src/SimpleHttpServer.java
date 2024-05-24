import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;

public class SimpleHttpServer implements HttpServerImpl {

    private static final String HTTP_NEW_LINE_SEPARATOR = "\r\n";
    private static final String HTTP_HEAD_BODY_SEPARATOR = HTTP_NEW_LINE_SEPARATOR + HTTP_NEW_LINE_SEPARATOR;
    private static final int HTTP_HEAD_BODY_SEPARATOR_BYTES = HTTP_HEAD_BODY_SEPARATOR.getBytes(StandardCharsets.US_ASCII).length;
    private static final int DEFAULT_BUFFER_SIZE = 10_000;
    private static final String CONTENT_LENGTH_HEADER = "content-length";
    private static final String CONNECTION_HEADER = "connection";
    private static final String CONNECTION_KEEP_ALIVE = "keep-alive";
    private final Executor requestsExecutor;
    private final int port;
    private final int connectionTimeout;
    private ServerSocket serverSocket;
    private boolean running;

    public SimpleHttpServer(Executor requestsExecutor, int port, int connectionTimeout) {
        this.requestsExecutor = requestsExecutor;
        this.port = port;
        this.connectionTimeout = connectionTimeout;
    }

    @Override
    public void start(HttpRequestHandler handler) {
        if (isRunning()) {
            throw new RuntimeException("Server is running on port %d".formatted(port));
        }
        startServer(handler);
    }

    private boolean isRunning() {
        return serverSocket != null && running;
    }

    private void startServer(HttpRequestHandler handler) {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
        } catch (Exception e) {
            throw new RuntimeException("Fail to start a SimpleHttpServer on %d".formatted(port), e);
        }
        new Thread(() -> {
            try {
                while (isRunning()) {
                    var connection = serverSocket.accept();
                    connection.setSoTimeout(connectionTimeout);
                    requestsExecutor.execute(() -> handleRequest(connection, handler));
                }

            } catch (Exception e) {
                if (isRunning()) {
                    stop();
                    throw new RuntimeException("Fail to accept next connection...", e);
                }
                System.out.println("Closing server...");
            }
        }).start();
    }


    private void handleRequest(Socket connection, HttpRequestHandler requestHandler) {
        try {
            var requestOpt = readRequest(connection);
            if (requestOpt.isEmpty()) {
                closeConnection(connection);
                return;
            }

            var request = requestOpt.get();
            printRequest(request);

            responseToRequest(connection, request, requestHandler);


            if (shouldReuseConnection(request.headers())) {
                handleRequest(connection, requestHandler);
            } else {
                closeConnection(connection);
            }
        } catch (SocketTimeoutException e) {
            System.out.println("Socket timeout, closing");
            closeConnection(connection);
        } catch (Exception e) {
            System.out.println("HTTP/1.1 404 Not Found");
            e.printStackTrace();
            closeConnection(connection);
        }

    }

    private Optional<HttpRequest> readRequest(Socket connection) throws Exception {
        var stream = connection.getInputStream();
        var rawRequestHead = readRawRequestHead(stream);

        if (rawRequestHead.length == 0) {
            return Optional.empty();
        }

        var requestHead = new String(rawRequestHead, StandardCharsets.US_ASCII);
        var lines = requestHead.split(HTTP_NEW_LINE_SEPARATOR);

        var line = lines[0];
        var methodUrl = line.split(" ");
        var method = methodUrl[0];
        var url = methodUrl[1];

        var headers = readHeaders(lines);

        var bodyLength = getExpectedLength(headers);

        byte[] body;
        if (bodyLength > 0) {
            var bodyStartIndex = requestHead.indexOf(HTTP_HEAD_BODY_SEPARATOR);
            if (bodyStartIndex > 0) {
                var readBody = Arrays.copyOfRange(rawRequestHead, bodyStartIndex + HTTP_HEAD_BODY_SEPARATOR_BYTES, rawRequestHead.length);
                body = readBody(stream, readBody, bodyLength);
            } else {
                body = new byte[0];
            }
        } else {
            body = new byte[0];
        }

        return Optional.of(new HttpRequest(method, url, headers, body));
    }


    private int getExpectedLength(Map<String, List<String>> headers) {
        try {
            return Integer.parseInt(headers.getOrDefault(CONTENT_LENGTH_HEADER, List.of("0")).getFirst());
        } catch (Exception e) {
            return 0;
        }
    }

    private byte[] readRawRequestHead(InputStream stream) throws Exception {
        var toRead = stream.available();
        if (toRead == 0) {
            toRead = DEFAULT_BUFFER_SIZE;
        }

        var buffer = new byte[toRead];
        var read = stream.read(buffer);
        if (read <= 0) {
            return new byte[0];
        }
        return read == toRead ? buffer : Arrays.copyOf(buffer, read);
    }

    private Map<String, List<String>> readHeaders(String[] lines) {
        var headers = new HashMap<String, List<String>>();

        for (int i = 1; i < lines.length; i++) {
            var line = lines[i];
            if (line.isEmpty()) {
                break;
            }

            var keyValue = line.split(":", 2);
            var key = keyValue[0].toLowerCase().strip();
            var value = keyValue[1].strip();

            headers.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        return headers;
    }

    private byte[] readBody(InputStream stream, byte[] readBody, int expectedBodyLength) throws Exception {
        if (readBody.length == expectedBodyLength) {
            return readBody;
        }

        var result = new ByteArrayOutputStream(expectedBodyLength);
        result.write(readBody);

        var readBytes = readBody.length;
        var buffer = new byte[DEFAULT_BUFFER_SIZE];

        while (readBytes < expectedBodyLength) {
            var read = stream.read(buffer);
            if (read > 0) {
                result.write(buffer, 0, read);
                readBytes += read;
            } else {
                break;
            }
        }
        return result.toByteArray();
    }


    private void closeConnection(Socket connection) {
        try {
            System.out.println("Closing connection...");
            connection.close();
        } catch (Exception e) {

        }
    }

    private void responseToRequest(Socket connection, HttpRequest request, HttpRequestHandler requestHandler) throws Exception {
        var res = requestHandler.handle(request);

        var os = connection.getOutputStream();


        var resString = new StringBuilder("HTTP/1.1 %d".formatted(res.responseCode()));

        res.headers().forEach((k, vs) ->
                vs.forEach(v ->
                        resString.append(HTTP_NEW_LINE_SEPARATOR)
                                .append(k)
                                .append(": ")
                                .append(v)));

        resString.append(HTTP_HEAD_BODY_SEPARATOR);

        os.write(resString.toString().getBytes(StandardCharsets.US_ASCII));

        if (res.body().length > 0) {
            os.write(res.body());
        }
    }

    private boolean shouldReuseConnection(Map<String, List<String>> headers) {
        return headers.getOrDefault(CONNECTION_HEADER, List.of(CONNECTION_KEEP_ALIVE))
                .getFirst()
                .equals(CONNECTION_KEEP_ALIVE);
    }

    private void printRequest(HttpRequest req) {
        System.out.println("Method: " + req.method());
        System.out.println("Url: " + req.url());
        System.out.println("Headers: ");
        req.headers().forEach((k, v) -> {
            System.out.println("%s - %s".formatted(k, v));
        });
        System.out.println("Body: ");
        if (req.body().length > 0) {
            System.out.println(new String(req.body(), StandardCharsets.UTF_8));
        } else {
            System.out.println("Body is empty");
        }
    }

    @Override
    public void stop() {
        if (isRunning()) {
            try {
                serverSocket.close();
            } catch (Exception e) {
                throw new RuntimeException("Fail to close the server", e);
            } finally {
                serverSocket = null;
                running = false;
            }
        }
    }
}
