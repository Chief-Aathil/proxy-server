package com.proxy.server.listener;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

@Service
public class ProxyServerListener {

    public static final int READ_TIMEOUT = 5000;
    public static final int PORT_FOR_PROXY_CLIENT = 9000;
    private final int PROXY_CLIENT_CONNECTION_TIMEOUT = 1000;
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private Socket proxyClientConnection = null;

    @PostConstruct
    public void init() {
        new Thread(this::startListening, "ProxyServerListenerThread").start();
        System.out.println("ProxyServerListenerThread started.");
    }

    private void startListening() {
        try {
            serverSocket = new ServerSocket(PORT_FOR_PROXY_CLIENT);
            serverSocket.setSoTimeout(PROXY_CLIENT_CONNECTION_TIMEOUT); // Timeout for accept() for graceful shutdown
            System.out.println("Proxy-Server waiting for connection from Proxy-Client on port " + PORT_FOR_PROXY_CLIENT);

            while (running && (proxyClientConnection == null || proxyClientConnection.isClosed())) {
                try {
                    // Accept only one connection (as per design for this phase)
                    proxyClientConnection = serverSocket.accept();
                    proxyClientConnection.setSoTimeout(READ_TIMEOUT); // Timeout for reads on this connection
                    System.out.println("Accepted connection from Proxy-Client: " + proxyClientConnection.getInetAddress().getHostAddress() + ":" + proxyClientConnection.getPort());

                    // Start a dedicated thread to handle communication with this specific proxy-client
                    // This thread will loop and read requests, then send responses
                    new Thread(this::handleProxyClientCommunication, "ProxyClientCommunicationHandler").start();

                } catch (SocketTimeoutException e) {
                    // Expected during graceful shutdown, or if no client connects for a while
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Proxy-Server Listener error accepting connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("FATAL: Could not start Proxy-Server ServerSocket on port " + PORT_FOR_PROXY_CLIENT + ": " + e.getMessage());
                // System.exit(1);
            }
        } finally {
            closeServerSocket();
        }
        System.out.println("ProxyServerListenerThread stopped.");
    }

    private void handleProxyClientCommunication() {
        if (proxyClientConnection == null || proxyClientConnection.isClosed()) {
            return; // No active connection to handle
        }

        String clientInfo = proxyClientConnection.getInetAddress().getHostAddress() + ":" + proxyClientConnection.getPort();
        System.out.println("ProxyClientCommunicationHandler started for: " + clientInfo);

        try (InputStream in = proxyClientConnection.getInputStream();
             OutputStream out = proxyClientConnection.getOutputStream()) {

            while (running && !proxyClientConnection.isClosed()) {
                // --- Phase 1.2.2: Simple Read and Fixed Response ---
                // Read a few bytes from the proxy-client
                byte[] buffer = new byte[1024];
                int bytesRead = -1;
                try {
                    bytesRead = in.read(buffer); // Blocks until data, EOF, or timeout
                } catch (SocketTimeoutException ste) {
                    // No data received for a while, keep listening
                    // System.out.println("Proxy-Client communication timed out (idle).");
                    continue; // Go back to loop and try reading again
                }

                if (bytesRead != -1) {
                    String receivedData = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                    System.out.println("Proxy-Server received from Proxy-Client (" + clientInfo + "): " + receivedData.trim());

                    // Send a fixed "Hello from Proxy-Server!" response
                    String response = "Hello from Proxy-Server!";
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    System.out.println("Proxy-Server sent fixed response to Proxy-Client.");
                } else {
                    System.out.println("Proxy-Client (" + clientInfo + ") disconnected.");
                    break; // Exit loop if client disconnected
                }
            }
        } catch (IOException e) {
            System.err.println("Proxy-Server communication error with Proxy-Client (" + clientInfo + "): " + e.getMessage());
        } finally {
            try {
                if (proxyClientConnection != null && !proxyClientConnection.isClosed()) {
                    proxyClientConnection.close();
                    System.out.println("Closed connection to Proxy-Client (" + clientInfo + ").");
                }
            } catch (IOException e) {
                System.err.println("Error closing Proxy-Client connection: " + e.getMessage());
            } finally {
                proxyClientConnection = null; // Clear connection reference
            }
        }
    }

    @PreDestroy
    public void destroy() {
        System.out.println("ProxyServerListener shutting down...");
        running = false; // Signal threads to stop
        closeServerSocket();
        // Give communication handler thread a moment to exit
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void closeServerSocket() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                System.out.println("Proxy-Server ServerSocket closed.");
            }
        } catch (IOException e) {
            System.err.println("Error closing Proxy-Server ServerSocket: " + e.getMessage());
        }
    }
}