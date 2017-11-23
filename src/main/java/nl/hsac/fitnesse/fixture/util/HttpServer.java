package nl.hsac.fitnesse.fixture.util;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.apache.http.ParseException;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.HTTP;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receiver for a callback from application being tested.
 */
public class HttpServer <T extends HttpResponse> {
    private static final Charset UTF8 = ContentType.parse(XmlHttpResponse.CONTENT_TYPE_XML_TEXT_UTF8).getCharset();
    public static final int MAX_PORT = 65535;

    private final T response;
    private final com.sun.net.httpserver.HttpServer server;
    private final InetSocketAddress address;
    private final String path;
    private final AtomicInteger requestsReceived = new AtomicInteger(0);
    private final Object lock = new Object();

    /**
     * Creates new.
     * @param anAddress address to bind on.
     * @param aPath context the server will serve (must start with '/').
     * @param aResponse response to send when request is received, request will
     *                  be added to it when this server receives one.
     */
    public HttpServer(InetSocketAddress anAddress, String aPath, T aResponse) {
        this(anAddress.getAddress(), anAddress.getPort(), aPath, aResponse);
    }

    /**
     * Creates new.
     * @param anAddress address to bind on.
     * @param port port to bind on.
     * @param aPath context the server will serve (must start with '/').
     * @param aResponse response to send when request is received, request will
     *                  be added to it when this server receives one.
     */
    public HttpServer(InetAddress anAddress, int port, String aPath, T aResponse) {
        this(anAddress, port, port, aPath, aResponse);
    }

    /**
     * Creates new.
     * @param anAddress address to bind on.
     * @param startPort lowest port to bind on.
     * @param maxPort highest number to bind on.
     * @param aPath context the server will serve (must start with '/').
     * @param aResponse response to send when request is received, request will
     *                  be added to it when this server receives one.
     */
    public HttpServer(InetAddress anAddress, int startPort, int maxPort, String aPath, T aResponse) {
        response = aResponse;
        path = aPath;
        server = createServer();
        bind(server, anAddress, startPort, maxPort);

        InetSocketAddress serverAddress = server.getAddress();
        if (serverAddress == null) {
            throw new RuntimeException(new BindException("Unable to bind to: " + anAddress.getHostAddress()));
        }
        int port = serverAddress.getPort();
        address = new InetSocketAddress(anAddress, port);

        server.createContext(aPath, getHandler(aResponse));
        server.start();
    }

    private com.sun.net.httpserver.HttpServer createServer() {
        try {
            return com.sun.net.httpserver.HttpServer.create();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * @return address the server listens on.
     */
    public InetSocketAddress getAddress() {
        return address;
    }

    /**
     * @return URL for requests to the server.
     */
    public String getURL() {
        InetSocketAddress address = getAddress();
        InetAddress hostAddress = address.getAddress();
        return "http://" + hostAddress.getHostAddress() + ":" + address.getPort() + path;
    }

    /**
     * @return response, with request filled (with last received request) if getRequestsReceived() is larger than 0.
     */
    public T getResponse() {
        return response;
    }

    /**
     * @return number of requests this server received.
     */
    public int getRequestsReceived() {
        return requestsReceived.get();
    }

    /**
     * @param maxWait ms to wait at most.
     * @return response with last request filled, if at least one was received.
     */
    public T waitForRequest(long maxWait) {
        long start = System.currentTimeMillis();
        try {
            while (requestsReceived.get() < 1
                    && (System.currentTimeMillis() - start) < maxWait) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        } finally {
            stopServer();
        }

        return getResponse();
    }

    /**
     * Stops server from listening.
     */
    public void stopServer() {
        synchronized (lock) {
            server.stop(0);
        }
    }

    /**
     * Finds free port number and binds the server to it.
     * @param server server to bind to port found.
     * @param address address to listen on.
     * @param startPort lowest allowed port.
     * @param maxPort highest (inclusive) port.
     */
    protected void bind(com.sun.net.httpserver.HttpServer server, InetAddress address, int startPort, int maxPort) {
        int port = -1;
        for (int possiblePort = startPort; port == -1 && possiblePort <= maxPort; possiblePort++) {
            try {
                InetSocketAddress s = new InetSocketAddress(address, possiblePort);
                server.bind(s, 1);
                port = possiblePort;
            } catch (IOException e) {
                // try next number
                continue;
            }
        }
    }

    protected HttpHandler getHandler(final T aResponse) {
        HttpHandler result = new HttpHandler() {
            @Override
            public void handle(HttpExchange he) throws IOException {
            // ensure we never handle multiple requests at the same time
            synchronized (lock) {
                OutputStream os = null;
                try {
                    String request;
                    if ("POST".equals(he.getRequestMethod()) || "PUT".equals(he.getRequestMethod())) {
                        InputStream is = he.getRequestBody();
                        request = FileUtil.streamToString(is, String.format("http %s request", he.getRequestMethod()));
                    } else {
                        request = String.format("%s: %s", he.getRequestMethod(), he.getRequestURI().toString());
                    }
                    aResponse.setRequest(request);

                    Headers heHeaders = he.getResponseHeaders();
                    Map<String, Object> responseHeaders = aResponse.getResponseHeaders();
                    for (Map.Entry<String, Object> headerEntry : responseHeaders.entrySet()) {
                        String headerName = headerEntry.getKey();
                        Object headerEntryValue = headerEntry.getValue();
                        heHeaders.add(headerName, headerEntryValue.toString());
                    }
                    byte[] responseBytes;
                    if (aResponse.getResponse() == null) {
                        responseBytes = new byte[0];
                    } else {
                        Charset charset = getCharSet(heHeaders);
                        responseBytes = aResponse.getResponse().getBytes(charset);
                    }
                    he.sendResponseHeaders(aResponse.getStatusCode(),
                                            responseBytes.length);
                    os = he.getResponseBody();
                    os.write(responseBytes);
                    os.flush();
                } finally {
                    incrementRequestsReceived();
                    if (os != null) {
                        os.close();
                    }
                }
            }
            }
        };
        return result;
    }

    protected Charset getCharSet(Headers heHeaders) {
        Charset charset = UTF8;
        String contentTypeHeader = heHeaders.getFirst(HTTP.CONTENT_TYPE);
        if (contentTypeHeader != null) {
            try {
                ContentType contentType = ContentType.parse(contentTypeHeader);
                Charset contentTypeCharset = contentType.getCharset();
                if (contentTypeCharset != null) {
                    charset = contentTypeCharset;
                }
            } catch (ParseException | UnsupportedCharsetException e) {
                // ignore, use default charset UTF8
            }
        }
        return charset;
    }

    protected int incrementRequestsReceived() {
        return requestsReceived.incrementAndGet();
    }
}
