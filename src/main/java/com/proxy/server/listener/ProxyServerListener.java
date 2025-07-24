// src/main/java/com/proxy/server/serverConnection/ProxyServerListener.java
package com.proxy.server.listener;

import com.proxy.server.protocol.ProxyProtocolConstants; // Import our constants
import com.proxy.server.utils.ByteStreamUtils; // Ensure this exists, same as client's
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Service
public class ProxyServerListener {

    private ServerSocket proxyClientServerSocket; // ServerSocket to listen for proxy-client connections
    private final int proxyClientPort = 9000; // Port for proxy-client to connect to
    private ExecutorService clientConnectionPool; // Manages threads for incoming proxy-client connections

    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        clientConnectionPool = Executors.newCachedThreadPool(); // Use a cached pool for flexibility

        try {
            proxyClientServerSocket = new ServerSocket(proxyClientPort);
            proxyClientServerSocket.setSoTimeout(1000); // Timeout for accept() to allow graceful shutdown
            System.out.println("Proxy-Server waiting for connection from Proxy-Client on port " + proxyClientPort);

            new Thread(this::acceptProxyClientConnection, "ProxyServerListenerThread").start();
            System.out.println("ProxyServerListenerThread started.");

        } catch (IOException e) {
            System.err.println("FATAL: Could not start Proxy-Server ServerSocket on port " + proxyClientPort + ": " + e.getMessage());
        }
    }

    private void acceptProxyClientConnection() {
        while (running && !proxyClientServerSocket.isClosed()) {
            try {
                Socket proxyClientSocket = proxyClientServerSocket.accept();
                System.out.println("Accepted connection from Proxy-Client: " + proxyClientSocket.getInetAddress().getHostAddress() + ":" + proxyClientSocket.getPort());
                // Submit a new handler for each accepted proxy-client connection
                clientConnectionPool.submit(new ProxyClientCommunicationHandler(proxyClientSocket));
            } catch (SocketTimeoutException e) {
                // Expected during graceful shutdown when accept() times out
                // System.out.println("Timed out for Proxy-Client connection setup");
            } catch (IOException e) {
                if (running) { // Only log if not shutting down
                    System.err.println("Error accepting Proxy-Client connection: " + e.getMessage());
                }
                try {
                    Thread.sleep(100); // Prevent busy-wait
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    running = false; // Stop accepting if interrupted
                }
            }
        }
        System.out.println("ProxyServerListenerThread stopped.");
    }

    @PreDestroy
    public void destroy() {
        System.out.println("ProxyServerListener shutting down...");
        running = false;
        try {
            if (proxyClientServerSocket != null && !proxyClientServerSocket.isClosed()) {
                proxyClientServerSocket.close();
                System.out.println("Proxy-Server ServerSocket closed.");
            }
        } catch (IOException e) {
            System.err.println("Error closing Proxy-Server ServerSocket: " + e.getMessage());
        }

        if (clientConnectionPool != null) {
            clientConnectionPool.shutdown();
            System.out.println("Attempting to shut down proxy client connection pool...");
            try {
                if (!clientConnectionPool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    clientConnectionPool.shutdownNow();
                    System.err.println("Proxy client connection pool did not terminate gracefully. Forced shutdown.");
                } else {
                    System.out.println("Proxy client connection pool shut down gracefully.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Proxy client connection pool shutdown interrupted.");
                clientConnectionPool.shutdownNow();
            }
        }
    }

    // --- Inner class for handling communication with an individual Proxy-Client ---
    private static class ProxyClientCommunicationHandler implements Runnable {
        private final Socket proxyClientSocket;
        private InputStream proxyClientIn;
        private OutputStream proxyClientOut;

        private Socket originServerSocket = null;
        private InputStream originServerIn = null;
        private OutputStream originServerOut = null;

        // Regex for Content-Length and Transfer-Encoding headers
        private static final Pattern CONTENT_LENGTH_PATTERN = Pattern.compile("Content-Length:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        private static final Pattern TRANSFER_ENCODING_PATTERN = Pattern.compile("Transfer-Encoding:\\s*chunked", Pattern.CASE_INSENSITIVE);


        public ProxyClientCommunicationHandler(Socket proxyClientSocket) {
            this.proxyClientSocket = proxyClientSocket;
        }

        @Override
        public void run() {
            String clientInfo = proxyClientSocket.getInetAddress().getHostAddress() + ":" + proxyClientSocket.getPort();
            System.out.println("ProxyClientCommunicationHandler started for: " + clientInfo);

            try {
                proxyClientIn = proxyClientSocket.getInputStream();
                proxyClientOut = proxyClientSocket.getOutputStream();

                // Set a general idle timeout for the *proxy-client* socket.
                // This timeout applies to *any* read operation on this socket.
                // It should be long enough to account for origin server response times.
                // Setting it here once applies it to all reads on this socket.
                // Let's make it 30 seconds for now, longer than origin timeout.
                proxyClientSocket.setSoTimeout(120000); // Set a read timeout for the client connection

                while (!proxyClientSocket.isClosed()) {
                    int messageType = -1;
                    try {
                        messageType = proxyClientIn.read();
                    } catch (SocketTimeoutException ste) {
                        System.out.println("Proxy-Client idle timeout for " + clientInfo + ". Closing connection.");
                        break;
                    }

                    if (messageType == -1) {
                        System.out.println("Proxy-Client " + clientInfo + " disconnected.");
                        break;
                    }

                    System.out.println("ProxyClientCommunicationHandler: Received message type: 0x" + Integer.toHexString(messageType) + " from " + clientInfo);

                    switch (messageType) {
                        case ProxyProtocolConstants.MSG_TYPE_CONNECT_REQUEST:
                            handleConnectRequest();
                            break;
                        case ProxyProtocolConstants.MSG_TYPE_HTTP_REQUEST:
                            handleHttpRequest();
                            break;
                        default:
                            System.err.println("ProxyClientCommunicationHandler: Unknown message type 0x" + Integer.toHexString(messageType) + " from " + clientInfo);
                            sendConnectFailed(proxyClientOut, "Unknown message type received: 0x" + Integer.toHexString(messageType));
                            break;
                    }
                }

            } catch (IOException e) {
                System.err.println("Proxy-Client communication I/O error for " + clientInfo + ": " + e.getMessage());
            } finally {
                closeAllConnections();
                System.out.println("Proxy-Client (" + clientInfo + ") disconnected.");
            }
        }

        private void handleConnectRequest() throws IOException {
            DataInputStream dataIn = new DataInputStream(proxyClientIn);
            String targetHost = null;
            int targetPort = -1;

            try {
                short hostLength = dataIn.readShort();
                byte[] hostBytes = new byte[hostLength];
                ByteStreamUtils.readFully(proxyClientIn, hostBytes, 0, hostLength);
                targetHost = new String(hostBytes, StandardCharsets.UTF_8);

                targetPort = dataIn.readInt();

                System.out.println("ProxyClientCommunicationHandler: CONNECT request for " + targetHost + ":" + targetPort);

                closeOriginConnection(); // Close any existing origin connection

                originServerSocket = new Socket(targetHost, targetPort);
                originServerSocket.setSoTimeout(120000); // Set a read timeout for the origin connection
                originServerIn = originServerSocket.getInputStream();
                originServerOut = originServerSocket.getOutputStream();

                System.out.println("ProxyClientCommunicationHandler: Successfully connected to origin: " + targetHost + ":" + targetPort);
                sendConnectSuccess(proxyClientOut);

            } catch (IOException e) {
                System.err.println("ProxyClientCommunicationHandler: Failed to connect to origin " + targetHost + ":" + targetPort + ": " + e.getMessage());
                sendConnectFailed(proxyClientOut, "Failed to connect to origin: " + e.getMessage());
                closeOriginConnection();
                // We're not re-throwing here, as sendConnectFailed already signals to the client.
                // The loop in run() will continue waiting for next message.
            }
        }

        private void handleHttpRequest() throws IOException {
            if (originServerSocket == null || originServerSocket.isClosed()) {
                System.err.println("ProxyClientCommunicationHandler: Received HTTP_REQUEST but no origin connection is established.");
                sendConnectFailed(proxyClientOut, "No origin connection established to forward HTTP request.");
                return;
            }

            DataInputStream dataIn = new DataInputStream(proxyClientIn);
            long contentLength = dataIn.readLong();
            byte[] rawHttpRequestBytes = new byte[(int) contentLength];
            ByteStreamUtils.readFully(proxyClientIn, rawHttpRequestBytes, 0, rawHttpRequestBytes.length);

            System.out.println("ProxyClientCommunicationHandler: Received HTTP request from proxy-client. Size: " + rawHttpRequestBytes.length);

            originServerOut.write(rawHttpRequestBytes);
            originServerOut.flush();
            System.out.println("ProxyClientCommunicationHandler: Forwarded HTTP request to origin server.");

            // --- NEW: Robustly read HTTP response from origin ---
            byte[] rawHttpResponseBytes = readHttpResponseFromOrigin(originServerIn);
            // --- END NEW ---

            System.out.println("ProxyClientCommunicationHandler: Received HTTP response from origin. Size: " + rawHttpResponseBytes.length);

            proxyClientOut.write(ProxyProtocolConstants.MSG_TYPE_HTTP_RESPONSE);
            new DataOutputStream(proxyClientOut).writeLong(rawHttpResponseBytes.length);
            proxyClientOut.write(rawHttpResponseBytes);
            proxyClientOut.flush();
            System.out.println("ProxyClientCommunicationHandler: Sent HTTP response to proxy-client.");

            // For now, continue to close after each request/response for simplicity.
            // We'll implement HTTP Keep-Alive (keeping the origin connection open) in a later step.
            closeOriginConnection();
        }

        /**
         * Reads a complete HTTP response from the origin server's InputStream.
         * This method parses headers to determine content length or chunked encoding.
         */
        private byte[] readHttpResponseFromOrigin(InputStream in) throws IOException {
            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();

            StringBuilder headersBuilder = new StringBuilder();
            Map<String, String> headers = new HashMap<>();
            String line;
            boolean firstLine = true;

            // Read headers
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (firstLine) {
                    // This is the status line, e.g., "HTTP/1.1 200 OK"
                    headersBuilder.append(line).append("\r\n");
                    firstLine = false;
                } else {
                    // Regular header line
                    headersBuilder.append(line).append("\r\n");
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String name = line.substring(0, colonIndex).trim();
                        String value = line.substring(colonIndex + 1).trim();
                        headers.put(name.toLowerCase(), value); // Store headers in lowercase for case-insensitive lookup
                    }
                }
            }
            headersBuilder.append("\r\n"); // Add the CRLF that signals end of headers

            responseBuffer.write(headersBuilder.toString().getBytes(StandardCharsets.ISO_8859_1)); // Add headers to buffer

            long contentLength = -1;
            boolean chunked = false;

            if (headers.containsKey("content-length")) {
                try {
                    contentLength = Long.parseLong(headers.get("content-length"));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid Content-Length header: " + headers.get("content-length"));
                }
            } else if (headers.containsKey("transfer-encoding") && headers.get("transfer-encoding").equals("chunked")) {
                chunked = true;
            }

            // Read body based on headers
            if (contentLength != -1) {
                System.out.println("ProxyClientCommunicationHandler: Reading fixed content length body: " + contentLength + " bytes.");
                byte[] body = new byte[(int) contentLength]; // Assuming body fits in int for now
                ByteStreamUtils.readFully(in, body, 0, body.length);
                responseBuffer.write(body);
            } else if (chunked) {
                System.out.println("ProxyClientCommunicationHandler: Reading chunked body.");
                readChunkedBody(in, responseBuffer);
            } else {
                // Fallback for cases without Content-Length or Transfer-Encoding (e.g., HTTP/1.0 without CL, or server closes)
                // This is less reliable and relies on socket timeout or server closing connection.
                System.out.println("ProxyClientCommunicationHandler: No Content-Length or Chunked encoding. Reading until stream end/timeout.");
                byte[] buffer = new byte[4096];
                int bytesRead;
                try {
                    while ((bytesRead = in.read(buffer)) != -1) {
                        responseBuffer.write(buffer, 0, bytesRead);
                    }
                } catch (SocketTimeoutException ste) {
                    System.out.println("ProxyClientCommunicationHandler: Origin server body read timed out. Assuming response complete.");
                }
            }

            return responseBuffer.toByteArray();
        }

        private String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
            int prevChar = -1; // To check for '\r' before '\n'
            int currentChar;

            // Loop to read characters until a newline sequence is found or stream ends
            while ((currentChar = in.read()) != -1) {
                lineBuffer.write(currentChar); // Add current char to buffer

                if (currentChar == '\n') { // Check for newline character
                    if (prevChar == '\r') {
                        // Found CRLF (Windows/HTTP standard), remove both from buffer
                        return new String(lineBuffer.toByteArray(), 0, lineBuffer.size() - 2, StandardCharsets.ISO_8859_1);
                    } else {
                        // Found LF (Unix), remove it from buffer
                        return new String(lineBuffer.toByteArray(), 0, lineBuffer.size() - 1, StandardCharsets.ISO_8859_1);
                    }
                }
                prevChar = currentChar; // Store current char as previous for next iteration
            }

            // If the loop finishes, it means the stream ended before a full line was read.
            // Return any partial data, or null if nothing was read.
            return lineBuffer.size() == 0 ? null : new String(lineBuffer.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        /**
         * Reads a chunked HTTP response body from the InputStream.
         * This now also uses the raw InputStream for reading chunk size lines.
         */
        private void readChunkedBody(InputStream in, ByteArrayOutputStream responseBuffer) throws IOException {
            String line;
            while (true) {
                line = readLine(in); // Use the new readLine(InputStream) directly on 'in'
                if (line == null) {
                    throw new IOException("Unexpected end of stream while reading chunk size.");
                }
                responseBuffer.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1)); // Write chunk size line back

                int chunkSize;
                try {
                    chunkSize = Integer.parseInt(line.trim().split(";")[0], 16); // Parse hex chunk size
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid chunk size format: " + line, e);
                }

                if (chunkSize == 0) {
                    // Last chunk (0\r\n)
                    System.out.println("ProxyClientCommunicationHandler: End of chunked body.");
                    // Read and write trailing headers (if any) and final CRLF
                    while ((line = readLine(in)) != null && !line.isEmpty()) { // Use new readLine(InputStream)
                        responseBuffer.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    }
                    responseBuffer.write("\r\n".getBytes(StandardCharsets.ISO_8859_1)); // Final CRLF
                    break;
                }

                byte[] chunkData = new byte[chunkSize];
                ByteStreamUtils.readFully(in, chunkData, 0, chunkSize);
                responseBuffer.write(chunkData); // Write chunk data

                line = readLine(in); // Read trailing CRLF for the chunk, use new readLine(InputStream)
                if (line == null || !line.isEmpty()) { // Expecting an empty line for CRLF
                    throw new IOException("Expected CRLF after chunk data, but got: '" + line + "'");
                }
                responseBuffer.write("\r\n".getBytes(StandardCharsets.ISO_8859_1)); // Write CRLF
            }
        }

        // --- Helper methods for sending responses to proxy-client ---
        private void sendConnectSuccess(OutputStream out) throws IOException {
            out.write(ProxyProtocolConstants.MSG_TYPE_CONNECT_SUCCESS);
            out.flush();
        }

        private void sendConnectFailed(OutputStream out, String reason) throws IOException {
            out.write(ProxyProtocolConstants.MSG_TYPE_CONNECT_FAILED);
            byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
            if (reasonBytes.length > Short.MAX_VALUE) {
                reasonBytes = "Reason too long".getBytes(StandardCharsets.UTF_8); // Truncate or use a generic message
            }
            new DataOutputStream(out).writeShort(reasonBytes.length);
            out.write(reasonBytes);
            out.flush();
        }

        // --- Helper methods for closing connections ---
        private void closeOriginConnection() {
            try {
                if (originServerSocket != null && !originServerSocket.isClosed()) {
                    originServerSocket.close();
                    System.out.println("Closed connection to origin server.");
                }
            } catch (IOException e) {
                System.err.println("Error closing origin server connection: " + e.getMessage());
            } finally {
                originServerSocket = null;
                originServerIn = null;
                originServerOut = null;
            }
        }

        private void closeAllConnections() {
            closeOriginConnection();
            try {
                if (proxyClientSocket != null && !proxyClientSocket.isClosed()) {
                    proxyClientSocket.close();
                    System.out.println("Proxy-Client socket closed.");
                }
            } catch (IOException e) {
                System.err.println("Error closing proxy-client socket: " + e.getMessage());
            }
        }
    }
}