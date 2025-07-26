package com.proxy.server.connection;

import com.proxy.server.communicator.ProxyServerCommunicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProxyServerConnectionManager {

    @Value("${proxy.server.listen.port}")
    private int listenPort;

    private ServerSocket serverSocket;
    private Socket clientTunnelSocket; // The single persistent tunnel socket
    private final ProxyServerCommunicator proxyServerCommunicator;
    private ExecutorService serverListenerExecutor;
    private Future<?> listenerTask;


    /**
     * Initializes the server socket and starts listening for the client tunnel connection.
     */
    @PostConstruct
    public void init() {
        serverListenerExecutor = Executors.newSingleThreadExecutor();
        listenerTask = serverListenerExecutor.submit(this::startListening);
    }

    /**
     * Starts listening for the single client tunnel connection.
     */
    private void startListening() {
        try {
            serverSocket = new ServerSocket(listenPort);
            log.info("Proxy Server listening for client tunnel on port {}", listenPort);

            // As per requirement constraint, we accept only one client tunnel connection.
            clientTunnelSocket = serverSocket.accept();
            clientTunnelSocket.setTcpNoDelay(true); // Optimize for lower latency
            log.info("Client tunnel connected from: {}", clientTunnelSocket.getInetAddress().getHostAddress());

            // Initialize the communicator with the socket's streams
            proxyServerCommunicator.initialize(clientTunnelSocket.getInputStream(), clientTunnelSocket.getOutputStream());

            // Start communicator's send and receive threads
            proxyServerCommunicator.start();

        } catch (IOException e) {
            log.error("Error starting or accepting connection on Proxy Server: {}", e.getMessage());
            // This could be due to port in use, or other network issues.
            // For now, we log and exit/stop. Reconnect logic will be in later phases.
        } finally {
            // Ensure server socket is closed if an error occurs before it's managed by PreDestroy
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    log.error("Error closing server socket: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Closes the server socket and client tunnel socket gracefully when the application shuts down.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProxyServerConnectionManager.");
        if (listenerTask != null) {
            listenerTask.cancel(true); // Interrupt the listening task
        }
        if (serverListenerExecutor != null) {
            serverListenerExecutor.shutdownNow(); // Force shutdown of executor
        }

        try {
            if (clientTunnelSocket != null && !clientTunnelSocket.isClosed()) {
                clientTunnelSocket.close();
                log.info("Closed client tunnel socket.");
            }
        } catch (IOException e) {
            log.error("Error closing client tunnel socket: {}", e.getMessage());
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                log.info("Closed server socket.");
            }
        } catch (IOException e) {
            log.error("Error closing server socket: {}", e.getMessage());
        } finally {
            proxyServerCommunicator.shutdown(); // Ensure communicator resources are also released
        }
    }

    /**
     * Check if the client tunnel connection is currently active.
     * @return true if connected, false otherwise.
     */
    public boolean isClientTunnelConnected() {
        return clientTunnelSocket != null && clientTunnelSocket.isConnected() && !clientTunnelSocket.isClosed();
    }
}