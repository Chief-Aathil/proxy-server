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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        // NEW: Socket for the connection to the actual Origin Server (e.g., example.com)
        private Socket originServerSocket = null;
        private InputStream originServerIn = null;
        private OutputStream originServerOut = null;


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

                // Loop to handle multiple requests (e.g., keep-alive) or streaming
                while (!proxyClientSocket.isClosed()) {
                    int messageType = -1;
                    try {
                        // Read the message type byte from proxy-client
                        proxyClientSocket.setSoTimeout(5000); // Set a read timeout
                        messageType = proxyClientIn.read();
                    } catch (SocketTimeoutException ste) {
                        System.out.println("Proxy-Client idle timeout for " + clientInfo + ". Closing connection.");
                        break; // Exit loop, close client connection due to inactivity
                    }

                    if (messageType == -1) {
                        System.out.println("Proxy-Client " + clientInfo + " disconnected.");
                        break; // End of stream, client disconnected
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
                            // Send an error response to proxy-client (e.g., 0x11 CONNECT_FAILED with a message)
                            sendConnectFailed(proxyClientOut, "Unknown message type received: 0x" + Integer.toHexString(messageType));
                            break; // Close connection
                    }
                }

            } catch (IOException e) {
                System.err.println("Proxy-Client communication I/O error for " + clientInfo + ": " + e.getMessage());
            } finally {
                closeAllConnections(); // Ensure all related sockets are closed
                System.out.println("Proxy-Client (" + clientInfo + ") disconnected.");
            }
        }

        /**
         * Handles MSG_TYPE_CONNECT_REQUEST from proxy-client.
         * Attempts to establish a connection to the target host/port.
         */
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

                // Close any existing origin connection before establishing a new one
                closeOriginConnection();

                // Attempt to connect to the actual origin server
                originServerSocket = new Socket(targetHost, targetPort);
                originServerIn = originServerSocket.getInputStream();
                originServerOut = originServerSocket.getOutputStream();

                System.out.println("ProxyClientCommunicationHandler: Successfully connected to origin: " + targetHost + ":" + targetPort);
                sendConnectSuccess(proxyClientOut); // Send success back to proxy-client

            } catch (IOException e) {
                System.err.println("ProxyClientCommunicationHandler: Failed to connect to origin " + targetHost + ":" + targetPort + ": " + e.getMessage());
                sendConnectFailed(proxyClientOut, "Failed to connect to origin: " + e.getMessage());
                closeOriginConnection(); // Ensure origin socket is closed on failure
                throw e; // Re-throw to propagate the error and potentially close proxyClientSocket
            }
        }

        /**
         * Handles MSG_TYPE_HTTP_REQUEST from proxy-client.
         * Forwards the HTTP request to the established origin server connection and sends back the response.
         */
        private void handleHttpRequest() throws IOException {
            if (originServerSocket == null || originServerSocket.isClosed()) {
                System.err.println("ProxyClientCommunicationHandler: Received HTTP_REQUEST but no origin connection is established.");
                sendConnectFailed(proxyClientOut, "No origin connection established to forward HTTP request.");
                return;
            }

            DataInputStream dataIn = new DataInputStream(proxyClientIn);
            long contentLength = dataIn.readLong();
            byte[] rawHttpRequestBytes = new byte[(int) contentLength]; // Assuming response size fits in int
            ByteStreamUtils.readFully(proxyClientIn, rawHttpRequestBytes, 0, rawHttpRequestBytes.length);

            System.out.println("ProxyClientCommunicationHandler: Received HTTP request from proxy-client. Size: " + rawHttpRequestBytes.length);

            // Forward the raw HTTP request to the origin server
            originServerOut.write(rawHttpRequestBytes);
            originServerOut.flush();
            System.out.println("ProxyClientCommunicationHandler: Forwarded HTTP request to origin server.");

            // Read the HTTP response from the origin server
            // This is simplified. In reality, you need to parse the HTTP response headers
            // to determine content length, chunked encoding, etc. For now, we'll
            // read until EOF or a reasonable timeout, or assume it's short.
            // A more robust solution would be a separate thread or non-blocking I/O for streaming.

            // For now, let's read the *entire* response until the stream is exhausted
            // or connection closes (simplistic but works for basic GETs).
            // WARNING: This is a blocking read and might hang if the origin server
            // doesn't close the connection or if it's keep-alive and sends nothing more.
            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;

            // Set a timeout for reading from the origin server
            originServerSocket.setSoTimeout(10000); // 10 seconds timeout for origin response

            try {
                while ((bytesRead = originServerIn.read(buffer)) != -1) {
                    responseBuffer.write(buffer, 0, bytesRead);
                    // Add logic here to detect end of HTTP response (e.g., Content-Length or chunked)
                    // For now, just keep reading. A real proxy needs more sophisticated logic.
                }
            } catch (SocketTimeoutException ste) {
                System.out.println("ProxyClientCommunicationHandler: Origin server read timed out. Assuming response complete.");
            } catch (IOException e) {
                System.err.println("ProxyClientCommunicationHandler: Error reading from origin server: " + e.getMessage());
                // Depending on the error, you might want to send a 502 Bad Gateway to the client
            }


            byte[] rawHttpResponseBytes = responseBuffer.toByteArray();
            System.out.println("ProxyClientCommunicationHandler: Received HTTP response from origin. Size: " + rawHttpResponseBytes.length);

            // Send the raw HTTP response back to proxy-client (0x12)
            proxyClientOut.write(ProxyProtocolConstants.MSG_TYPE_HTTP_RESPONSE);
            new DataOutputStream(proxyClientOut).writeLong(rawHttpResponseBytes.length);
            proxyClientOut.write(rawHttpResponseBytes);
            proxyClientOut.flush();
            System.out.println("ProxyClientCommunicationHandler: Sent HTTP response to proxy-client.");

            // This simplified logic means that after one request/response, the origin
            // connection might be closed or not, depending on origin behavior.
            // For proper HTTP/1.1 keep-alive, we'd need to parse 'Connection: keep-alive'
            // and keep the origin socket open. For now, it's simpler.
            // For future (Phase 3), we might close the origin connection here.
            closeOriginConnection(); // Simplistic: Close after each request for now.
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
            closeOriginConnection(); // Close origin first
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