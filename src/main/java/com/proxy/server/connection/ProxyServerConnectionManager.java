package com.proxy.server.connection;

import com.proxy.server.communicator.ProxyServerCommunicator;
import com.proxy.server.config.ServerConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
@Component
public class ProxyServerConnectionManager {

    private final ServerConfig serverConfig;
    private final ProxyServerCommunicator serverCommunicator;
    private final AtomicBoolean isClientConnectionLost = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private ExecutorService connectionAcceptorExecutor;

    @PostConstruct
    public void init() {
        running = true;
        connectionAcceptorExecutor = Executors.newSingleThreadExecutor();
        connectionAcceptorExecutor.submit(this::startListeningForClient);
    }

    /**
     * Listens for and accepts the single persistent connection from the proxy-client.
     * Handles initial connection and subsequent reconnections.
     */
    private void startListeningForClient() {
        Thread.currentThread().setName("Server-Connection-Acceptor");
        log.info("ProxyServerConnectionManager started, listening for client on port {}", serverConfig.getListenPort());

        try {
            serverSocket = new ServerSocket(serverConfig.getListenPort());
            while (running) {
                Socket clientTunnelSocket = null;
                try {
                    log.info("Waiting for client connection on port {}...", serverConfig.getListenPort());
                    clientTunnelSocket = serverSocket.accept(); // Blocks until client connects
                    clientTunnelSocket.setTcpNoDelay(true);
                    log.info("Accepted new client tunnel connection from: {}", clientTunnelSocket.getInetAddress().getHostAddress());

                    // If there was an old communicator, shut it down gracefully
                    if (serverCommunicator.isRunning()) {
                        log.warn("New client connection received while old communicator was still running. Shutting down old communicator.");
                        serverCommunicator.shutdown();
                    }
                    serverCommunicator.initialize(clientTunnelSocket, this::onClientConnectionLoss);
                    serverCommunicator.start();
                    isClientConnectionLost.set(false); // Reset signal on successful connection

                    // Periodically check status
                    while (running && serverCommunicator.isRunning() && !isClientConnectionLost.get()) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Server connection acceptor interrupted while waiting for communicator status.");
                            break;
                        }
                    }
                    log.info("Client communicator stopped. Preparing to accept new connection if running.");

                } catch (IOException e) {
                    if (running) { // Only log error if not intentionally shutting down
                        log.error("Error accepting client tunnel connection: {}", e.getMessage());
                    } else {
                        log.info("ProxyServerConnectionManager stopped accepting connections.");
                    }
                } catch (Exception e) {
                    log.error("Unexpected error in server connection acceptor loop: {}", e.getMessage(), e);
                } finally {
                    // Ensure the socket is closed if an error occurred before communicator took over
                    if (clientTunnelSocket != null && !clientTunnelSocket.isClosed()) {
                        try {
                            clientTunnelSocket.close();
                            log.debug("Closed client tunnel socket after handling or error.");
                        } catch (IOException e) {
                            log.error("Error closing client tunnel socket: {}", e.getMessage());
                        }
                    }
                    // If the communicator stopped due to connection loss, the loop will continue to accept a new one
                }
            }
        } catch (IOException e) {
            log.error("Could not start ProxyServerConnectionManager on port {}: {}", serverConfig.getListenPort(), e.getMessage());
            running = false; // Mark as not running if startup fails
        } finally {
            cleanupServerSocket();
            log.info("ProxyServerConnectionManager stopped.");
        }
    }

    /**
     * Callback method invoked by ProxyServerCommunicator when connection to client is lost.
     */
    private void onClientConnectionLoss() {
        log.warn("Connection to client (ship proxy) lost. Signalling for new connection acceptance.");
        isClientConnectionLost.set(true);
        // This will make the startListeningForClient() to accept new connection with client.
    }

    /**
     * Cleans up the server socket.
     */
    private void cleanupServerSocket() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                log.info("Closed server socket.");
            } catch (IOException e) {
                log.error("Error closing server socket: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProxyServerConnectionManager.");
        running = false;

        // Shut down the connection acceptor executor gracefully
        if (connectionAcceptorExecutor != null) {
            connectionAcceptorExecutor.shutdown();
            try {
                if (!connectionAcceptorExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("Connection acceptor executor did not terminate in time, forcing shutdown.");
                    connectionAcceptorExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Connection acceptor executor shutdown interrupted.");
                connectionAcceptorExecutor.shutdownNow();
            }
        }

        // Ensure the server socket is closed to unblock accept()
        cleanupServerSocket();

        // Shut down communicator and its own threads and processors
        if (serverCommunicator.isRunning()) {
            serverCommunicator.shutdown();
        }
        log.info("ProxyServerConnectionManager shutdown complete.");
    }
}