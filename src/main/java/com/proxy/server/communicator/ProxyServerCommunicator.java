package com.proxy.server.communicator;

import com.proxy.server.communicator.FramedMessage; // Import FramedMessage from client package
import com.proxy.server.processor.HttpProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProxyServerCommunicator {

    // Queue for outgoing FramedMessages to be sent by the send thread
    private final BlockingQueue<FramedMessage> outgoingQueue = new LinkedBlockingQueue<>();

    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private ExecutorService executorService;
    private volatile boolean running = false;
    private final HttpProcessor httpProcessor;
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
     *
     * @param message The FramedMessage to send.
     * @throws InterruptedException If the thread is interrupted while adding to the queue.
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

                    // For Phase 1, we'll handle PING/PONG directly here.
                    // In later phases, this will involve dispatching to HttpProcessor/HttpsProcessor.
                    handleReceivedMessage(receivedMessage);
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
     * Handles a received FramedMessage. For Phase 1, this includes basic PING/PONG.
     * In later phases, this will dispatch to appropriate processors.
     * @param message The received FramedMessage.
     */
    private void handleReceivedMessage(FramedMessage message) throws InterruptedException {
        switch (message.getMessageType()) {
            case HEARTBEAT_PING:
                log.info("Received HEARTBEAT_PING from client: {}. Sending PONG.", message.getRequestID());
                send(new FramedMessage(FramedMessage.MessageType.HEARTBEAT_PONG, message.getRequestID(), new byte[0]));
                break;
            case HTTP_REQUEST:
                log.info("Received HTTP_REQUEST from client: {}. Dispatching to HttpProcessor.", message.getRequestID());
                httpProcessor.processHttpRequest(message); // Dispatch to HttpProcessor
                break;
            case HTTPS_CONNECT:
                // TODO: In Phase 3, dispatch to HttpsProcessor
                log.warn("Received HTTPS_CONNECT from client: {}. HttpsProcessor not yet implemented.", message.getRequestID());
                send(new FramedMessage(FramedMessage.MessageType.CONTROL_500_ERROR, message.getRequestID(), "HTTPS Not Supported Yet".getBytes(StandardCharsets.UTF_8)));
                break;
            case HEARTBEAT_PONG: // Server should not receive PONG unless it sent a PING, which it doesn't in this phase.
            case HTTP_RESPONSE:  // Server should only send HTTP_RESPONSE, not receive it (unless it's a very specific reverse proxy scenario, not here)
            case HTTPS_DATA:     // Handled by HttpsProcessor in Phase 3
            case CONTROL_200_OK: // Control messages are for internal tunnel management, not direct data.
            case CONTROL_TUNNEL_CLOSE: // Control messages
            default:
                log.warn("Received unhandled message type: {} with ID: {}", message.getMessageType(), message.getRequestID());
                // In later phases, this will dispatch to HttpProcessor/HttpsProcessor
                break;
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