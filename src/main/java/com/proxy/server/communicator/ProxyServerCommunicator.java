package com.proxy.server.communicator;

import com.proxy.server.processor.HttpProcessor;
import com.proxy.server.processor.HttpsProcessor;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProxyServerCommunicator {

    // Queue for outgoing FramedMessages to be sent by the send thread
    private final BlockingQueue<FramedMessage> outgoingQueue = new LinkedBlockingQueue<>();
    // Map to correlate request IDs with CompletableFutures for responses (for HTTP requests)
    private final ConcurrentHashMap<UUID, CompletableFuture<FramedMessage>> correlationMap = new ConcurrentHashMap<>();
    // Map to store active HttpsProcessor instances, one per active HTTPS tunnel (requestID)
    private final ConcurrentHashMap<UUID, HttpsProcessor> activeHttpsProcessorsMap = new ConcurrentHashMap<>();
    private final HttpProcessor httpProcessor;
    private final ObjectProvider<HttpsProcessor> httpsProcessorProvider;

    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private ExecutorService executorService;
    @Getter
    private volatile boolean running = false;
    private Runnable connectionLossCallback;

    /**
     * Initializes the communicator with the client tunnel socket and a callback for connection loss.
     * This method must be called after a successful socket connection is accepted.
     *
     * @param clientTunnelSocket The accepted client tunnel socket.
     * @param connectionLossCallback A callback to be invoked if the connection is lost.
     */
    public void initialize(Socket clientTunnelSocket, Runnable connectionLossCallback) throws IOException {
        this.dataInputStream = new DataInputStream(clientTunnelSocket.getInputStream());
        this.dataOutputStream = new DataOutputStream(clientTunnelSocket.getOutputStream());
        this.executorService = Executors.newFixedThreadPool(2); // One for sending, one for receiving
        this.connectionLossCallback = connectionLossCallback;
        log.info("ProxyServerCommunicator initialized with client tunnel streams.");
    }

    /**
     * Starts the dedicated send and receive threads.
     */
    public void start() {
        if (dataInputStream == null || dataOutputStream == null) {
            log.error("Communicator not initialized. Cannot start threads.");
            return;
        }
        running = true;
        executorService.submit(this::sendLoop);
        executorService.submit(this::receiveLoop);
        log.info("ProxyServerCommunicator send and receive loops started.");
    }

    /**
     * Adds a FramedMessage to the outgoing queue for sending.
     * This method is non-blocking.
     *
     * @throws IOException If the communicator is not running (connection lost).
     */
    public void send(FramedMessage message) throws InterruptedException, IOException {
        if (!running) {
            log.warn("Communicator is not running. Message not sent: {}", message);
            throw new IOException("Communicator not running, connection likely lost.");
        }
        outgoingQueue.put(message); // Blocking if queue is full
        log.debug("Added message to outgoing queue: {}", message);
    }

    /**
     * Sends a FramedMessage and returns a CompletableFuture that will be completed
     * when a response with the matching requestID is received.
     * This is primarily for HTTP responses.
     *
     * @throws InterruptedException If the thread is interrupted while sending the message.
     * @throws IOException If the communicator is not running.
     */
    public CompletableFuture<FramedMessage> sendAndAwaitResponse(FramedMessage requestMessage) throws InterruptedException, IOException { // Added IOException
        CompletableFuture<FramedMessage> future = new CompletableFuture<>();
        correlationMap.put(requestMessage.getRequestID(), future);
        try {
            send(requestMessage);
        } catch (IOException e) {
            correlationMap.remove(requestMessage.getRequestID());
            future.completeExceptionally(e); // Propagate send failure to future
            throw e; // Re-throw for immediate caller handling
        }
        return future;
    }

    /**
     * The main loop for sending messages from the outgoing queue.
     */
    private void sendLoop() {
        Thread.currentThread().setName("Server-Send-Thread");
        log.info("Server send loop started.");
        while (running) {
            try {
                FramedMessage message = outgoingQueue.take(); // Blocks until a message is available
                byte[] bytes = message.toBytes();
                dataOutputStream.writeInt(bytes.length);
                dataOutputStream.write(bytes);
                dataOutputStream.flush();
                log.debug("Sent message: {}", message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Server send loop interrupted. Shutting down.");
                running = false;
            } catch (IOException e) {
                log.error("Error sending message: {}. Client tunnel likely lost. Shutting down send loop.", e.getMessage());
                handleConnectionLoss(); // Notify connection manager
                running = false; // Signal to stop loops
            } catch (Exception e) {
                log.error("Unexpected error in server send loop: {}", e.getMessage(), e);
                handleConnectionLoss(); // Notify connection manager
            }
        }
        log.info("Server send loop stopped.");
    }

    /**
     * The main loop for receiving messages from the input stream.
     */
    private void receiveLoop() {
        Thread.currentThread().setName("Server-Receive-Thread");
        log.info("Server receive loop started.");
        while (running) {
            try {
                int frameLength = dataInputStream.readInt();
                if (frameLength < 0) {
                    log.error("Received invalid frame length: {}. Client tunnel likely corrupted. Shutting down receive loop.", frameLength);
                    handleConnectionLoss(); // Notify connection manager
                    running = false;
                    break;
                }
                byte[] frameBytes = new byte[frameLength];
                dataInputStream.readFully(frameBytes);

                try (ByteArrayInputStream bis = new ByteArrayInputStream(frameBytes);
                     DataInputStream dis = new DataInputStream(bis)) {
                    FramedMessage receivedMessage = FramedMessage.fromStream(dis);
                    log.debug("Received message: {}", receivedMessage);
                    dispatchReceivedMessage(receivedMessage);
                }
            } catch (IOException e) {
                log.error("Error receiving message: {}. Client tunnel likely lost. Shutting down receive loop.", e.getMessage());
                handleConnectionLoss(); // Notify connection manager
                running = false; // Signal to stop loops
            } catch (Exception e) {
                log.error("Unexpected error in server receive loop: {}", e.getMessage(), e);
                handleConnectionLoss(); // Notify connection manager
            }
        }
        log.info("Server receive loop stopped.");
    }

    /**
     * Dispatches received FramedMessages to the appropriate processor or CompletableFuture.
     * This is the central routing logic for all incoming messages from the client.
     *
     * @param message The received FramedMessage.
     */
    private void dispatchReceivedMessage(FramedMessage message) {
        // First, check if there's a CompletableFuture waiting for this message (eg:HTTP response)
        CompletableFuture<FramedMessage> future = correlationMap.remove(message.getRequestID());
        if (future != null) {
            future.complete(message);
            log.debug("Completed pending future for ID: {} (Type: {})", message.getRequestID(), message.getMessageType());
            return; // Message handled by a waiting future
        }

        // If no waiting future, dispatch based on message type
        switch (message.getMessageType()) {
            case HTTP_REQUEST -> handleHttpRequest(message);
            case HTTPS_CONNECT -> handleConnect(message);
            case HTTPS_DATA -> delegateToHttpsProcessor(message);
            case CONTROL_TUNNEL_CLOSE -> closeTunnel(message);
            case HEARTBEAT_PING -> sendPong(message);
            default ->
                    log.warn("Received unhandled message type: {} with ID: {}. Discarding.", message.getMessageType(), message.getRequestID());
        }
    }

    private void handleHttpRequest(FramedMessage message) {
        httpProcessor.processHttpRequest(message)
                .thenAccept(response -> {
                    try {
                        send(response);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Thread interrupted while sending HTTP response: {}", e.getMessage());
                    } catch (IOException e) {
                        log.error("IO error while sending HTTP response: {}", e.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    log.error("Error processing HTTP request: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    private void handleConnect(FramedMessage message) {
        HttpsProcessor newHttpsProcessor = httpsProcessorProvider.getObject();
        activeHttpsProcessorsMap.put(message.getRequestID(), newHttpsProcessor);
        newHttpsProcessor.handleConnectRequest(message.getRequestID(), message.getPayload());
    }

    private void delegateToHttpsProcessor(FramedMessage message) {
        HttpsProcessor httpsProcessor = activeHttpsProcessorsMap.get(message.getRequestID());
        if (httpsProcessor != null) {
            httpsProcessor.handleHttpsData(message.getRequestID(), message.getPayload());
        } else {
            log.warn("Received HTTPS_DATA for unknown or already closed tunnel ID: {}. Discarding.", message.getRequestID());
            try {
                send(new FramedMessage(FramedMessage.MessageType.CONTROL_TUNNEL_CLOSE, message.getRequestID(), new byte[0]));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Failed to send tunnel close for unknown HTTPS_DATA (interrupted): {}", e.getMessage());
            } catch (IOException e) {
                log.error("Failed to send tunnel close for unknown HTTPS_DATA (IO error): {}", e.getMessage());
            }
        }
    }

    private void closeTunnel(FramedMessage message) {
        // Client initiated a tunnel close. Find the corresponding HttpsProcessor and clean up.
        HttpsProcessor httpsProcessor = activeHttpsProcessorsMap.remove(message.getRequestID());
        if (httpsProcessor != null) {
            // The HttpsProcessor's shutdown method will handle its internal cleanup
            httpsProcessor.handleTunnelClose(message.getRequestID());
        } else {
            log.warn("Received CONTROL_TUNNEL_CLOSE for unknown or already closed tunnel ID: {}. Discarding.", message.getRequestID());
        }
    }

    private void sendPong(FramedMessage message) {
        try {
            send(new FramedMessage(FramedMessage.MessageType.HEARTBEAT_PONG, message.getRequestID(), new byte[0]));
            log.info("Responded to HEARTBEAT_PING for ID: {}", message.getRequestID());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to send HEARTBEAT_PONG for ID {} (interrupted): {}", message.getRequestID(), e.getMessage());
        } catch (IOException e) {
            log.error("Failed to send HEARTBEAT_PONG for ID {} (IO error): {}", message.getRequestID(), e.getMessage());
        }
    }

    /**
     * Handles connection loss by calling the registered callback and failing all pending futures.
     */
    private void handleConnectionLoss() {
        // Prevent multiple loss notifications if already shutting down
        if (!running) {
            return;
        }
        running = false; // Mark as not running immediately
        log.error("ProxyServerCommunicator: Connection to client lost. Notifying manager and failing pending operations.");
        if (connectionLossCallback != null) {
            connectionLossCallback.run();
        }

        // Fail all pending CompletableFutures for HTTP requests
        correlationMap.forEach((requestID, future) -> {
            future.completeExceptionally(new IOException("Connection to client lost."));
            log.warn("Failing HTTP request future {} due to connection loss.", requestID);
        });
        correlationMap.clear();

        // Trigger shutdown on all active HttpsProcessor instances
        activeHttpsProcessorsMap.forEach((id, processor) -> {
            processor.shutdown();
        });
        activeHttpsProcessorsMap.clear();
    }

    /**
     * Shuts down the communicator, stopping threads and closing streams.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ProxyServerCommunicator.");
        running = false; // Signal threads to stop

        if (executorService != null) {
            executorService.shutdownNow();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("ProxyServerCommunicator executor did not terminate in time.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Shutdown interrupted.");
            }
        }

        // Clear any pending futures and active HTTPS processors
        correlationMap.forEach((uuid, future) -> future.cancel(true));
        correlationMap.clear();

        // Trigger shutdown on all active HttpsProcessor instances
        activeHttpsProcessorsMap.forEach((id, processor) -> {
            processor.shutdown();
        });
        activeHttpsProcessorsMap.clear();
        outgoingQueue.clear();

        try {
            if (dataInputStream != null) dataInputStream.close();
            if (dataOutputStream != null) dataOutputStream.close();
        } catch (IOException e) {
            log.error("Error closing communicator streams: {}", e.getMessage());
        }
        log.info("ProxyServerCommunicator shutdown complete.");
    }
}