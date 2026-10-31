package com.fnphoto.tv.testing;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Loopback-only HTTP/1.1 fixture, without JDK module APIs excluded by Android's bootclasspath. */
public final class LocalHttpServer {
    public interface Handler { void handle(Exchange exchange) throws IOException; }
    public static final class Headers extends TreeMap<String, String> {
        Headers() { super(String.CASE_INSENSITIVE_ORDER); }
        public String getFirst(String key) { return get(key); }
        public void set(String key, String value) { put(key, value); }
    }
    public static final class Exchange {
        private final Socket socket;
        private final Headers request = new Headers(), response = new Headers();
        private String path;
        Exchange(Socket socket) throws IOException {
            this.socket = socket;
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line = reader.readLine();
            if (line == null) throw new EOFException();
            path = line.split(" ")[1].split("\\?", 2)[0];
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if (separator > 0) request.set(line.substring(0, separator), line.substring(separator + 1).trim());
            }
        }
        public Headers getRequestHeaders() { return request; }
        public Headers getResponseHeaders() { return response; }
        public OutputStream getResponseBody() throws IOException { return socket.getOutputStream(); }
        public void sendResponseHeaders(int status, long length) throws IOException {
            response.set("Connection", "close");
            if (length != 0) response.set("Content-Length", String.valueOf(Math.max(0, length)));
            StringBuilder text = new StringBuilder("HTTP/1.1 " + status + " Fixture\r\n");
            for (Map.Entry<String, String> header : response.entrySet()) text.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            text.append("\r\n");
            socket.getOutputStream().write(text.toString().getBytes("UTF-8"));
            socket.getOutputStream().flush();
        }
        public void close() { try { socket.close(); } catch (IOException ignored) { } }
    }
    private final ServerSocket socket;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Set<Socket> connections = Collections.synchronizedSet(new HashSet<>());
    private LocalHttpServer(InetSocketAddress address) throws IOException {
        if (!address.getAddress().isLoopbackAddress()) throw new IllegalArgumentException("Loopback only");
        socket = new ServerSocket(); socket.bind(address);
    }
    public static LocalHttpServer create(InetSocketAddress address, int backlog) throws IOException { return new LocalHttpServer(address); }
    public void createContext(String path, Handler handler) { handlers.put(path, handler); }
    public InetSocketAddress getAddress() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
    public void start() {
        Thread accept = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept(); connections.add(client);
                    Thread worker = new Thread(() -> {
                        try {
                            Exchange exchange = new Exchange(client);
                            Handler handler = handlers.get(exchange.path);
                            if (handler != null) handler.handle(exchange);
                            else { exchange.sendResponseHeaders(404, -1); exchange.close(); }
                        } catch (IOException ignored) { }
                        finally { connections.remove(client); try { client.close(); } catch (IOException ignored) { } }
                    }); worker.setDaemon(true); worker.start();
                } catch (IOException ignored) { }
            }
        }); accept.setDaemon(true); accept.start();
    }
    public void stop(int delay) {
        try { socket.close(); } catch (IOException ignored) { }
        synchronized (connections) { for (Socket client : connections) try { client.close(); } catch (IOException ignored) { } }
    }
}
