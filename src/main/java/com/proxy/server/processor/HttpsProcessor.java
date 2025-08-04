package com.proxy.server.processor;

import com.proxy.server.communicator.FramedMessage;
import com.proxy.server.communicator.ProxyServerCommunicator;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@Scope("prototype")
@Component
public class HttpsProcessor {

    public static final Pattern CONNECT_PATTERN = Pattern.compile("CONNECT\\s+([^:]+):(\\d+)");
    private final ProxyServerCommunicator serverCommunicator;
    private final ConcurrentHashMap<UUID, Socket> activeTargetSocketsMap = new ConcurrentHashMap<>();
    // Executor for handling bidirectional data transfer for each tunnel
    private final ExecutorService tunnelExecutor = Executors.newCachedThreadPool();

    public void handleConnectRequest(UUID requestID, byte[] connectRequestBytes) {
        String connectRequestLine = getConnectRequestLine(connectRequestBytes);
        log.info("HttpsProcessor: Received CONNECT request for ID {}: {}", requestID, connectRequestLine);

        // Parse host and port from the CONNECT request line
        Matcher matcher = CONNECT_PATTERN.matcher(connectRequestLine);
        if (!matcher.find()) {
            log.error("HttpsProcessor: Malformed CONNECT request for ID {}: {}", requestID, connectRequestLine);
            return;
        }
        String targetHost = matcher.group(1);
        int targetPort = Integer.parseInt(matcher.group(2));

        Socket targetSocket = null;
        try {
            // Open a direct TCP socket to the target host and port
            targetSocket = new Socket(targetHost, targetPort);
            targetSocket.setTcpNoDelay(true);
            log.info("HttpsProcessor: Successfully connected to target {}:{} for ID: {}", targetHost, targetPort, requestID);

            // Store the target socket mapping
            activeTargetSocketsMap.put(requestID, targetSocket);

            // Send CONTROL_200_OK back to the client
            FramedMessage okMessage = new FramedMessage(
                    FramedMessage.MessageType.CONTROL_200_OK,
                    requestID,
                    new byte[0]
            );
            serverCommunicator.send(okMessage);
            log.debug("HttpsProcessor: Sent CONTROL_200_OK for ID: {}", requestID);

            // Start bidirectional data transfer for this tunnel
            startBidirectionalTunnel(requestID, targetSocket);

        } catch (IOException e) {
            log.error("HttpsProcessor: Failed to connect to target {}:{} for ID {}: {}", targetHost, targetPort, requestID, e.getMessage());
            cleanupTunnel(requestID, targetSocket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("HttpsProcessor: Interrupted while handling CONNECT request for ID {}.", requestID);
            cleanupTunnel(requestID, targetSocket);
        } catch (Exception e) {
            log.error("HttpsProcessor: Unexpected error during CONNECT handling for ID {}: {}", requestID, e.getMessage(), e);
            cleanupTunnel(requestID, targetSocket);
        }
    }

    private static String getConnectRequestLine(byte[] connectRequestBytes) {
        return new String(connectRequestBytes,
                0,
                Math.min(connectRequestBytes.length, 256),
                StandardCharsets.ISO_8859_1).split("\r\n")[0];
    }

    /**
     * Starts two concurrent tasks for a given HTTPS tunnel:
     * 1. Reading from the client (via ProxyServerCommunicator) and writing to the target server.
     * 2. Reading from the target server and writing to the client (via ProxyServerCommunicator).
     *
     * @param requestID    The unique ID of the tunnel.
     * @param targetSocket The socket connected to the target web server.
     */
    private void startBidirectionalTunnel(UUID requestID, Socket targetSocket) {
        log.debug("HttpsProcessor: Starting bidirectional tunnel for ID: {}", requestID);

        // Task 1: Read from client (via ProxyServerCommunicator) -> Write to TargetServer
        // This is handled by a method in ProxyServerCommunicator that dispatches HTTPS_DATA
        // to this HttpsProcessor. This HttpsProcessor needs a way to receive that data.
        // For now, the ProxyServerCommunicator's dispatch logic will call `handleHttpsData` on this instance.

        // Task 2: Read from TargetServer -> Write to client (via ProxyServerCommunicator)
        tunnelExecutor.submit(() -> {
            Thread.currentThread().setName("Server-TargetRead-ID-" + requestID);
            byte[] buffer = new byte[4096];
            int bytesRead;
            try (InputStream targetIn = targetSocket.getInputStream()) {
                while (!targetSocket.isClosed() && (bytesRead = targetIn.read(buffer)) != -1) {
                    if (bytesRead > 0) {
                        byte[] payload = new byte[bytesRead];
                        System.arraycopy(buffer, 0, payload, 0, bytesRead);
                        FramedMessage dataMessage = new FramedMessage(
                                FramedMessage.MessageType.HTTPS_DATA,
                                requestID,
                                payload
                        );
                        serverCommunicator.send(dataMessage);
                        log.trace("HttpsProcessor: Sent {} bytes of HTTPS_DATA from target to client for ID: {}", bytesRead, requestID);
                    }
                }
                log.info("HttpsProcessor: Target socket closed gracefully for ID: {}", requestID);
            } catch (IOException e) {
                if (targetSocket.isClosed()) {
                    log.debug("HttpsProcessor: Target socket ID {} already closed due to prior error.", requestID);
                } else {
                    log.warn("HttpsProcessor: I/O error during HTTPS tunnel data transfer from target for ID {}: {}", requestID, e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("HttpsProcessor: Target read loop for ID {} interrupted.", requestID);
            } finally {
                // When target socket closes, send CONTROL_TUNNEL_CLOSE to client
                sendTunnelCloseSignal(requestID);
                cleanupTunnel(requestID, targetSocket);
            }
        });
    }

    /**
     * Handles incoming HTTPS_DATA FramedMessages from the client.
     * Writes the payload (encrypted data) to the corresponding target server socket.
     *
     * @param requestID The unique ID of the tunnel.
     * @param dataBytes The raw encrypted data from the client.
     */
    public void handleHttpsData(UUID requestID, byte[] dataBytes) {
        Socket targetSocket = activeTargetSocketsMap.get(requestID);
        if (targetSocket != null && !targetSocket.isClosed()) {
            try {
                targetSocket.getOutputStream().write(dataBytes);
                targetSocket.getOutputStream().flush();
                log.trace("HttpsProcessor: Wrote {} bytes of HTTPS_DATA to target for ID: {}", dataBytes.length, requestID);
            } catch (IOException e) {
                log.warn("HttpsProcessor: Failed to write HTTPS_DATA to target socket for ID {}: {}. Target socket likely closed.", requestID, e.getMessage());
                cleanupTunnel(requestID, targetSocket);
                // Also send CONTROL_TUNNEL_CLOSE to client if this is a critical failure
                sendTunnelCloseSignal(requestID);
            }
        } else {
            log.warn("HttpsProcessor: Received HTTPS_DATA for unknown or already closed tunnel ID: {}. Discarding.", requestID);
            // Optionally send CONTROL_TUNNEL_CLOSE back to client if this happens frequently,
            // as it indicates a desynchronized tunnel state.
            sendTunnelCloseSignal(requestID); // Attempt to signal client to clean up
        }
    }

    /**
     * Handles incoming CONTROL_TUNNEL_CLOSE FramedMessages from the client.
     * Cleans up the corresponding target server socket.
     *
     * @param requestID The unique ID of the tunnel to close.
     */
    public void handleTunnelClose(UUID requestID) {
        log.info("HttpsProcessor: Received CONTROL_TUNNEL_CLOSE for ID: {}", requestID);
        cleanupTunnel(requestID, activeTargetSocketsMap.get(requestID));
    }

    /**
     * Sends a CONTROL_TUNNEL_CLOSE message to the client (ship proxy) to signal
     * the tear-down of a specific HTTPS tunnel from the server's side.
     * This is called when the target server closes its connection or an error occurs.
     */
    private void sendTunnelCloseSignal(UUID requestID) {
        log.info("HttpsProcessor: Sending CONTROL_TUNNEL_CLOSE to client for ID: {}", requestID);
        FramedMessage closeMessage = new FramedMessage(
                FramedMessage.MessageType.CONTROL_TUNNEL_CLOSE,
                requestID,
                new byte[0]
        );
        try {
            serverCommunicator.send(closeMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("HttpsProcessor: Failed to send CONTROL_TUNNEL_CLOSE for ID {}: {}", requestID, e.getMessage());
        } catch (IOException e) {
            log.error("HttpsProcessor: Failed to send CONTROL_TUNNEL_CLOSE for ID {}. Reason:{}", requestID, e.getMessage());
        }
    }

    /**
     * Cleans up resources associated with a specific HTTPS tunnel.
     * Closes the target socket and removes its mapping.
     *
     * @param requestID    The unique ID of the tunnel.
     * @param targetSocket The target socket to close (can be null).
     */
    private void cleanupTunnel(UUID requestID, Socket targetSocket) {
        if (targetSocket != null) {
            try {
                if (!targetSocket.isClosed()) {
                    targetSocket.close();
                    log.debug("HttpsProcessor: Target socket closed for ID: {}", requestID);
                }
            } catch (IOException e) {
                log.error("HttpsProcessor: Error closing target socket for ID {}: {}", requestID, e.getMessage());
            }
        }
        activeTargetSocketsMap.remove(requestID);
        log.debug("HttpsProcessor: Removed target socket mapping for ID: {}", requestID);
    }

    @PreDestroy
    public void shutdown() {
        log.info("HttpsProcessor: Shutting down. Closing all active target sockets.");
        activeTargetSocketsMap.forEach((id, socket) -> {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                log.error("HttpsProcessor: Error closing target socket {} during shutdown: {}", id, e.getMessage());
            }
        });
        activeTargetSocketsMap.clear();

        if (tunnelExecutor != null) {
            tunnelExecutor.shutdown();
            try {
                if (!tunnelExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("HttpsProcessor: Tunnel executor did not terminate in time, forcing shutdown.");
                    tunnelExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("HttpsProcessor: Tunnel executor shutdown interrupted.");
                tunnelExecutor.shutdownNow();
            }
        }
        log.info("HttpsProcessor: Shutdown complete.");
    }
}
