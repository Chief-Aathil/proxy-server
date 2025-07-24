Alright, let's consolidate all the necessary concerns and design elements for your **Dedicated ServerSocket approach** for the **proxy-client** (ship-side) and **proxy-server** (offshore-side) into a single, comprehensive response.

---

## Dedicated ServerSocket Proxy System: Concerns and Design Elements

This design aims to process all HTTP/S requests from the ship through a single outgoing TCP connection to a custom offshore proxy server, sequentially.

### I. Core Design Principles

1.  **Single Outgoing TCP Connection:**
    * **Concern:** This is the hard constraint. All traffic from `proxy-client` to `proxy-server` must flow over one pre-established, long-lived TCP connection.
    * **Design:**
        * A dedicated `ServerConnMgr` thread (on the `proxy-client`) will establish and manage this single `Socket` connection to the `proxy-server`.
        * This thread will handle all reads and writes to/from this connection.

2.  **Sequential Processing:**
    * **Concern:** If 3 requests come in parallel, they must be fulfilled Request 1, then Request 2, then Request 3.
    * **Design:**
        * A **bounded `BlockingQueue`** (`requestQueue`) within the `proxy-client` will serve as the central coordination point.
        * `ClientHandler` threads (producers) will `put()` `ProxyRequestTask` objects onto this queue.
        * The `ServerConnMgr` thread (consumer) will `take()` `ProxyRequestTask` objects from this queue **one at a time**, process them fully (including receiving the response from `proxy-server` for HTTP, or managing the full lifecycle of a tunnel for HTTPS), and only then proceed to the next item.

3.  **HTTP/S Proxy Compatibility:**
    * **Concern:** Browser settings (e.g., Chrome) should point to `proxy-client:8080`.
    * **Design:**
        * The `proxy-client` will open a `ServerSocket` on port 8080 and mimic standard HTTP proxy behavior (parsing request lines, handling `CONNECT`).

### II. Component Design & Threading Model

#### A. Proxy-Client (Ship-Side)

1.  **`ProxyClientApp` (Spring Boot Main/Context):**
    * Responsible for application lifecycle: initialization (`@PostConstruct`), shutdown (`@PreDestroy`).
    * Manages the instantiation and wiring of core components (`ClientAcceptor`, `ServerConnMgr`).

2.  **`ClientAcceptor` Thread (1 thread):**
    * **Role:** Listens for new incoming TCP connections from browsers/clients on `ServerSocket` (port 8080).
    * **Action:** When a new connection arrives, it accepts the `Socket` and submits a `ClientConnectionHandler` task to a thread pool.
    * **Concerns:** Robust `ServerSocket.accept()` loop, error handling for connection failures.

3.  **`ClientConnectionHandler` Threads (Pooled: N threads from `ExecutorService`):**
    * **Role:** Each thread handles a single client's TCP connection (from browser to `proxy-client`).
    * **Actions:**
        * **Request Parsing:** Reads the initial request line from `clientSocket.getInputStream()`.
        * **HTTP vs. HTTPS Differentiation:**
            * If `CONNECT` method: Sends "HTTP/1.1 200 Connection Established" back to the browser. Creates a `ProxyRequestTask` of type `CONNECT` (containing target host:port and a `CompletableFuture<Void> tunnelSetupFuture`). Puts this task on `requestQueue`.
            * If other HTTP method (GET/POST/etc.): Reads the entire raw HTTP request (headers + body). Creates a `ProxyRequestTask` of type `HTTP` (containing raw request bytes and a `CompletableFuture<byte[]> httpResponseFuture`). Puts this task on `requestQueue`.
        * **Backpressure:** Uses `requestQueue.put(task)` which will **block** the `ClientHandler` thread if the queue is full, applying backpressure to the incoming client connection.
        * **Response Handling:**
            * For HTTP: Blocks on `httpResponseFuture.get()` until the `ServerConnMgr` completes it with the raw HTTP response. Writes this response back to `clientSocket.getOutputStream()`.
            * For CONNECT: Blocks on `tunnelSetupFuture.get()`. Once completed (signaled by `ServerConnMgr` that the tunnel is ready), the `ClientHandler` is done with *its* role in setting up the tunnel, and the byte copying (handled by `ServerConnMgr`) takes over.
        * **Keep-Alive & Pipelining:** After processing one request, the `ClientHandler` loops back to read the *next* request from the *same client connection* (if `Connection: keep-alive` is active), effectively un-pipelining requests and processing them one-by-one per client connection.
        * **Connection Closure:** Manages graceful closure of `clientSocket` when client disconnects (EOF on `InputStream`) or on errors.
    * **Concerns:** Proper HTTP request parsing (handling `Content-Length`, `Transfer-Encoding: chunked`), robust error handling for client-side I/O.

4.  **`RequestQueue` (`BlockingQueue<ProxyRequestTask>`, Bounded Capacity):**
    * **Role:** Buffers requests from multiple `ClientHandler` threads for sequential consumption by the `ServerConnMgr`.
    * **Design:** Use `LinkedBlockingQueue` or `ArrayBlockingQueue` with a predefined `capacity`.
    * **Backpressure Mechanism:** When full, `put()` operations block producers (`ClientHandler` threads).
    * **Concerns:** Appropriate capacity sizing (memory vs. responsiveness), handling `InterruptedException` if a blocked `ClientHandler` is interrupted.

5.  **`ServerConnMgr` Thread (1 thread):**
    * **Role:** The *single consumer* of `ProxyRequestTask`s from the `requestQueue` and the *sole manager* of the persistent `Socket` to the `proxy-server`. Enforces sequential processing globally.
    * **Actions:**
        * **Persistent Connection Management:** Establishes and maintains the single `Socket` connection to the `proxy-server`. Implements retry logic with backoff for re-establishing the connection if it breaks.
        * **Sequential Processing Loop:** Continuously calls `requestQueue.take()` (blocks if queue is empty) to get the next `ProxyRequestTask`.
        * **Custom Framing Protocol:** Writes framed messages to the `proxy-server`'s `OutputStream` based on `ProxyRequestTask.Type`.
        * **Response Handling:**
            * For HTTP: Reads framed raw HTTP response from `proxy-server`, then calls `task.httpResponseFuture.complete(responseBytes)` to unblock the `ClientHandler`.
            * For CONNECT: Sends `CONNECT Initiation Frame`. Then, spawns *internal* threads (or uses non-blocking I/O) for `copyBytes` to handle the bidirectional data flow between `clientInputStream`/`clientOutputStream` and the single persistent connection to `proxy-server`. **Crucially, the `ServerConnMgr` waits for the *entire `copyBytes` process* (i.e., the tunnel) to complete** before taking the next item from the `requestQueue`. It calls `task.tunnelSetupFuture.complete(null)` when the tunnel setup is complete, and later when the `copyBytes` fully terminates.
    * **Concerns:** Robust connection management (reconnection, timeouts), accurate framing/de-framing, handling EOF signals during tunneling, potential I/O errors on the single connection.

#### B. Proxy-Server (Offshore-Side)

1.  **`ProxyServerApp` (Standalone Java/Spring Boot):**
    * Manages its own `ServerSocket` for accepting the *single* incoming connection from the `proxy-client`.

2.  **`TunnelListener` Thread (1 thread):**
    * **Role:** Listens on a specific port (e.g., 9000) for the single `proxy-client` connection.
    * **Action:** Once the connection is established, dedicates a single thread to continuously read framed messages from it.

3.  **`RequestProcessor` Thread (1 thread - same as `TunnelListener` or dedicated):**
    * **Role:** De-frames and processes messages received from the `proxy-client` over the persistent connection.
    * **Actions:**
        * **De-framing:** Reads Message Type (0x01, 0x02, 0x03, 0x04) and Length Prefixes using `DataInputStream`.
        * **HTTP Request Processing (Type `0x01`):** Reconstructs raw HTTP request. Uses `java.net.http.HttpClient` (Java 11+) to make the actual request to the Internet. Reads the response, frames it, and sends it back to `proxy-client`.
        * **CONNECT Tunnel Initiation (Type `0x02`):** Parses target host:port. Establishes a *new* `Socket` connection to the target Internet server. Spawns new threads (or uses async I/O) to perform bidirectional byte copying between the *persistent connection's streams* and the *new target Internet connection's streams*. This continues until the tunnel is closed.
        * **CONNECT Tunnel Data (Type `0x03`):** Receives raw bytes and forwards them to the active target Internet connection.
        * **CONNECT Tunnel Close (Type `0x04`):** Receives signal to close the corresponding target Internet connection.
    * **Concerns:** Correct HTTP client usage, managing multiple active `CONNECT` tunnels (each needing its own `Socket` to the Internet and byte-copying threads *from the proxy-server's perspective*), graceful shutdown of target Internet connections, robust error handling.

### III. Critical Concerns & Solutions

1.  **Custom Framing Protocol:**
    * **Concern:** Raw TCP streams offer no message boundaries or type information.
    * **Solution:** Implement a byte-based framing protocol (e.g., `Message Type (1 byte) + Length (4/8 bytes) + Payload Bytes`). This applies to both directions of the `proxy-client` <-> `proxy-server` connection.

2.  **Reliability of Single Persistent Connection:**
    * **Concern:** If the connection between `proxy-client` and `proxy-server` drops, the entire system stops.
    * **Solution:** The `ServerConnMgr` thread must implement robust **reconnection logic** with exponential backoff. It should detect `IOException`s (e.g., `SocketException`, `EOFException`) on read/write operations and trigger a reconnection attempt.

3.  **Memory Exhaustion (Backpressure):**
    * **Concern:** Incoming requests from browsers could overwhelm the single processing line to the `proxy-server`.
    * **Solution:** Use a **bounded `BlockingQueue`** for `requestQueue`. When the queue is full, `ClientHandler` threads (producers) attempting `put()` will block, effectively preventing new requests from consuming memory until existing ones are processed. This implicitly applies backpressure to the clients.

4.  **Sequential Processing Implementation:**
    * **Concern:** Strict "one by one" processing is required.
    * **Solution:** The `ServerConnMgr` thread `take()`s only one item at a time from the `BlockingQueue`. For HTTP requests, it waits for the full response. For `CONNECT` tunnels, it must wait for the *entire tunnel session* to conclude (i.e., the browser closes its connection, or a timeout occurs) before taking the next item.

5.  **HTTP vs. HTTPS (`CONNECT`) Handling:**
    * **Concern:** HTTPS requires tunneling raw encrypted bytes, not HTTP parsing.
    * **Solution:**
        * **Proxy-Client:** Identifies `CONNECT` method, sends `200 Connection Established`, then simply encapsulates all subsequent bytes from the client's socket into data frames and sends them to `proxy-server`.
        * **Proxy-Server:** On receiving a `CONNECT Initiation Frame`, establishes a *new* TCP connection to the target HTTPS server. Then, it performs bidirectional byte copying between the persistent connection (with `proxy-client`) and the new target connection, encapsulating data into `Tunnel Data Frames`.

6.  **Concurrency Management:**
    * **Concern:** Managing multiple client connections, a single queue, and a single outgoing connection.
    * **Solution:**
        * `ExecutorService` (thread pool) for `ClientHandler`s to manage incoming client concurrency efficiently.
        * `BlockingQueue` for thread-safe producer-consumer coordination.
        * `CompletableFuture` to allow `ClientHandler` threads to wait for responses from `ServerConnMgr` without directly blocking on the `ServerConnMgr` itself.
        * Internal threads/async I/O for bidirectional byte copying within `CONNECT` tunnels on both `proxy-client` and `proxy-server`.

7.  **Error Handling & Robustness:**
    * **Concern:** Network errors, malformed requests, timeouts, unexpected disconnects.
    * **Solution:** Comprehensive `try-catch` blocks around all I/O operations. Logging for debugging. Graceful client connection closure. Implementing timeouts on long-running reads (`Socket.setSoTimeout()`).

8.  **Resource Management:**
    * **Concern:** Preventing socket leaks, thread leaks, and memory bloat.
    * **Solution:** Use try-with-resources for `InputStream`/`OutputStream`/`Socket` where possible. Ensure all sockets and streams are closed in `finally` blocks. Carefully manage byte buffers (reusing where possible). Shutdown `ExecutorService` pools cleanly on application exit (`@PreDestroy`).

This detailed breakdown provides the blueprint for building your resilient and compliant proxy system using the Dedicated ServerSocket approach in Java.