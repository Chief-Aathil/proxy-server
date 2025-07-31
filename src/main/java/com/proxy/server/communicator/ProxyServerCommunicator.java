package com.proxy.server.communicator;

import com.proxy.server.processor.HttpProcessor;
import com.proxy.server.processor.HttpsProcessor;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private final ConcurrentHashMap<UUID, HttpsProcessor> activeHttpsProcessors = new ConcurrentHashMap<>();

    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private ExecutorService executorService;
    private volatile boolean running = false;
    private final HttpProcessor httpProcessor;
    private final ObjectProvider<HttpsProcessor> httpsProcessorProvider;


    /**
     * Initializes the communicator with the input and output streams of the client tunnel socket.
     * This method must be called after a successful socket connection is accepted.
     *
     * @param inputStream  The InputStream from the accepted client tunnel socket.
     * @param outputStream The OutputStream from the accepted client tunnel socket.
     */
    public void initialize(InputStream inputStream, OutputStream outputStream) {
        this.dataInputStream = new DataInputStream(inputStream);
        this.dataOutputStream = new DataOutputStream(outputStream);
        this.executorService = Executors.newFixedThreadPool(2); // One for sending, one for receiving
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
     */
    public void send(FramedMessage message) throws InterruptedException {
        if (!running) {
            log.warn("Communicator is not running. Message not sent: {}", message);
            return;
        }
        outgoingQueue.put(message); // Blocking put if queue is full (unlikely for LinkedBlockingQueue)
        log.debug("Added message to outgoing queue: {}", message);
    }

    /**
     * Sends a FramedMessage and returns a CompletableFuture that will be completed
     * when a response with the matching requestID is received.
     * This is primarily for HTTP responses.
     *
     * @param requestMessage The FramedMessage to send.
     * @return A CompletableFuture that will hold the response FramedMessage.
     * @throws InterruptedException If the thread is interrupted while sending the message.
     */
    public CompletableFuture<FramedMessage> sendAndAwaitResponse(FramedMessage requestMessage) throws InterruptedException {
        CompletableFuture<FramedMessage> future = new CompletableFuture<>();
        correlationMap.put(requestMessage.getRequestID(), future);
        send(requestMessage);
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
                running = false; // Signal to stop loops
                // TODO: In Phase 4, notify ProxyServerConnectionManager about connection loss
            } catch (Exception e) {
                log.error("Unexpected error in server send loop: {}", e.getMessage(), e);
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
                running = false; // Signal to stop loops
                // TODO: In Phase 4, notify ProxyServerConnectionManager about connection loss
            } catch (Exception e) {
                log.error("Unexpected error in server receive loop: {}", e.getMessage(), e);
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
        // First, check if there's a CompletableFuture waiting for this message (e.g., HTTP response)
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
            case HEARTBEAT_PONG ->
                // Client's response to our ping, no action needed here as it's handled by correlationMap (if we ping)
                    log.debug("Received HEARTBEAT_PONG for ID: {}", message.getRequestID());
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
                    }
                })
                .exceptionally(ex -> {
                    log.error("Error processing HTTP request: {}", ex.getMessage(), ex);
                    return null;
                });
    }

    private void handleConnect(FramedMessage message) {
        HttpsProcessor newHttpsProcessor = httpsProcessorProvider.getObject();
        activeHttpsProcessors.put(message.getRequestID(), newHttpsProcessor);
        newHttpsProcessor.handleConnectRequest(message.getRequestID(), message.getPayload());
    }

    private void delegateToHttpsProcessor(FramedMessage message) {
        HttpsProcessor httpsProcessor = activeHttpsProcessors.get(message.getRequestID());
        if (httpsProcessor != null) {
            httpsProcessor.handleHttpsData(message.getRequestID(), message.getPayload());
        } else {
            log.warn("Received HTTPS_DATA for unknown or already closed tunnel ID: {}. Discarding.", message.getRequestID());
            // Optionally send CONTROL_TUNNEL_CLOSE back if this indicates a desynchronized state
            try {
                send(new FramedMessage(FramedMessage.MessageType.CONTROL_TUNNEL_CLOSE, message.getRequestID(), new byte[0]));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Failed to send tunnel close for unknown HTTPS_DATA: {}", e.getMessage());
            }
        }
    }

    private void closeTunnel(FramedMessage message) {
        // Client initiated a tunnel close. Find the corresponding HttpsProcessor and clean up.
        HttpsProcessor httpsProcessor = activeHttpsProcessors.remove(message.getRequestID());
        if (httpsProcessor != null) {
            httpsProcessor.handleTunnelClose(message.getRequestID());
            // The HttpsProcessor's shutdown method will handle its internal cleanup
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
            log.error("Failed to send HEARTBEAT_PONG for ID {}: {}", message.getRequestID(), e.getMessage());
        }
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
        activeHttpsProcessors.forEach((id, processor) -> {
            processor.shutdown();
        });
        activeHttpsProcessors.clear();
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