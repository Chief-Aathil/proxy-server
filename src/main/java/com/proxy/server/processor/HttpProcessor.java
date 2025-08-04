package com.proxy.server.processor;

import com.proxy.server.communicator.FramedMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@Component
public class HttpProcessor {

    public static final int CONNECTION_TIMEOUT_TO_TARGET_SERVER = 10;
    private static final Pattern REQUEST_LINE_PATTERN = Pattern.compile("(?<method>[A-Z]+)\\s+(?<uri>[^\\s]+)\\s+HTTP/1\\.(?<minor>0|1)");
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?<name>[^:]+):\\s*(?<value>.*)");

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT_TO_TARGET_SERVER)).followRedirects(HttpClient.Redirect.NORMAL) // Follow redirects by default
                .build();
        log.info("HttpProcessor initialized with HttpClient.");
    }

    /**
     * Processes an incoming HTTP_REQUEST FramedMessage.
     * Parses the raw HTTP request, makes the actual web request, and returns a CompletableFuture
     * that will complete with the FramedMessage representing the raw HTTP response.
     *
     * @param httpRequest The FramedMessage containing the raw HTTP request payload.
     * @return A CompletableFuture that completes with the HTTP_RESPONSE FramedMessage.
     */
    public CompletableFuture<FramedMessage> processHttpRequest(FramedMessage httpRequest) {
        UUID requestID = httpRequest.getRequestID();
        byte[] rawRequestPayload = httpRequest.getPayload();
        log.info("HttpProcessor received HTTP_REQUEST for ID: {}", requestID);

        try {
            // Parse the raw HTTP request bytes
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
            String method = parseRawHttpRequestAndSetBuilder(rawRequestPayload, requestBuilder);

            // Build the HttpRequest with the determined method and body publisher
            HttpRequest actualHttpRequest = requestBuilder.method(method, requestBuilder.build().bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();

            log.debug("Making actual HTTP request to: {} for ID: {}", actualHttpRequest.uri(), requestID);
            // Send actual HTTP request asynchronously and chain completion
            return httpClient.sendAsync(actualHttpRequest, HttpResponse.BodyHandlers.ofByteArray()).handle((httpResponse, throwable) -> {
                try {
                    byte[] responseBytes;
                    if (throwable != null) {
                        log.error("Error making actual HTTP request to {}: {}. For ID: {}", actualHttpRequest.uri(), throwable.getMessage(), requestID);
                        responseBytes = createErrorResponse(502, "Bad Gateway", "Proxy server could not connect to target.");
                    } else {
                        responseBytes = buildRawHttpResponse(httpResponse);
                        log.debug("Successfully received HTTP response from target for ID: {}", requestID);
                    }
                    return new FramedMessage(FramedMessage.MessageType.HTTP_RESPONSE, requestID, responseBytes);
                } catch (Exception e) {
                    log.error("Critical error building proxy response for ID {}: {}", requestID, e.getMessage(), e);
                    try {
                        return new FramedMessage(FramedMessage.MessageType.HTTP_RESPONSE, requestID, createErrorResponse(500, "Internal Server Error", "Proxy server encountered an unexpected error."));
                    } catch (IOException ioException) {
                        log.error("Failed to create fallback error response for ID {}: {}", requestID, ioException.getMessage());
                        // Fallback to a very basic error if even the error response creation fails
                        return new FramedMessage(FramedMessage.MessageType.HTTP_RESPONSE, requestID, "HTTP/1.1 500 Internal Server Error\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    }
                }
            });
        } catch (Exception e) {
            log.error("Error parsing or preparing HTTP request for ID {}: {}", requestID, e.getMessage(), e);
            try {
                // If parsing fails, create a 400 Bad Request response
                return CompletableFuture.completedFuture(new FramedMessage(FramedMessage.MessageType.HTTP_RESPONSE, requestID, createErrorResponse(400, "Bad Request", "Invalid HTTP request format.")));
            } catch (IOException ioException) {
                log.error("Failed to create 400 error response for ID {}: {}", requestID, ioException.getMessage());
                return CompletableFuture.completedFuture(new FramedMessage(FramedMessage.MessageType.HTTP_RESPONSE, requestID, "HTTP/1.1 500 Internal Server Error\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)));
            }
        }
    }

    /**
     * Parses the raw HTTP request bytes, sets the HttpRequest.Builder,
     * and returns the HTTP method string. The body publisher is set directly on the builder.
     */
    private String parseRawHttpRequestAndSetBuilder(byte[] rawRequestBytes, HttpRequest.Builder requestBuilder) throws IOException, java.net.URISyntaxException {
        ByteArrayInputStream bis = new ByteArrayInputStream(rawRequestBytes);
        BufferedReader reader = new BufferedReader(new InputStreamReader(bis, StandardCharsets.ISO_8859_1));
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            throw new IOException("Empty or invalid request line.");
        }
        Matcher requestMatcher = REQUEST_LINE_PATTERN.matcher(requestLine);
        if (!requestMatcher.matches()) {
            throw new IOException("Malformed request line: " + requestLine);
        }
        String method = requestMatcher.group("method");
        String pathOrAbsoluteUri = requestMatcher.group("uri");
        String line;
        String host = null;
        int contentLength = 0;
        String transferEncoding = null;

        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            Matcher headerMatcher = HEADER_PATTERN.matcher(line);
            if (headerMatcher.matches()) {
                String headerName = headerMatcher.group("name");
                String headerValue = headerMatcher.group("value");
                if (headerName.equalsIgnoreCase("Host")) {
                    host = headerValue;
                } else if (headerName.equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(headerValue);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid Content-Length header: {}", headerValue);
                    }
                } else if (headerName.equalsIgnoreCase("Transfer-Encoding")) {
                    transferEncoding = headerValue;
                }
                // Exclude headers that proxies typically manage or remove
                if (!headerName.equalsIgnoreCase("Host") && !headerName.equalsIgnoreCase("Proxy-Connection") && !headerName.equalsIgnoreCase("Connection") && !headerName.equalsIgnoreCase("Keep-Alive") && !headerName.equalsIgnoreCase("Proxy-Authorization") && !headerName.equalsIgnoreCase("TE") && !headerName.equalsIgnoreCase("Upgrade")) {
                    requestBuilder.header(headerName, headerValue);
                }
            } else {
                log.warn("Malformed header line: {}", line);
            }
        }

        URI targetUri;
        if (pathOrAbsoluteUri.startsWith("http://") || pathOrAbsoluteUri.startsWith("https://")) {
            targetUri = new URI(pathOrAbsoluteUri);
        } else if (host != null) {
            targetUri = new URI("http://" + host + pathOrAbsoluteUri);
        } else {
            throw new IOException("Could not determine target URI (no absolute URI or Host header)");
        }
        requestBuilder.uri(targetUri);

        // Read body bytes if present (after headers have been read by BufferedReader)
        ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
        if (contentLength > 0) {
            // Read fixed-length body
            byte[] bodyBuffer = new byte[4096];
            int totalBytesRead = 0;
            int bytesToRead = contentLength;

            while (bytesToRead > 0 && (reader.ready() || bis.available() > 0)) {
                int read = bis.read(bodyBuffer, 0, Math.min(bodyBuffer.length, bytesToRead));
                if (read == -1) break; // End of stream
                bodyStream.write(bodyBuffer, 0, read);
                totalBytesRead += read;
                bytesToRead -= read;
            }
            if (totalBytesRead < contentLength) {
                log.warn("Incomplete body read. Expected {} bytes, read {}.", contentLength, totalBytesRead);
            }
        } else if ("chunked".equalsIgnoreCase(transferEncoding)) {
            log.warn("Chunked transfer encoding is not fully supported in this phase for request. Body will be empty.");
        }
        // Set the body publisher directly on the builder
        requestBuilder.setHeader("X-Original-Method", method); // Store method for later use in builder.method()
        requestBuilder.setHeader("X-Internal-Body-Data-Present", String.valueOf(bodyStream.toByteArray().length > 0)); // Indicate body presence
        requestBuilder.method(method, bodyStream.toByteArray().length > 0 ? HttpRequest.BodyPublishers.ofByteArray(bodyStream.toByteArray()) : HttpRequest.BodyPublishers.noBody());
        return method;
    }

    /**
     * Builds the raw HTTP response bytes (status line + headers + body) from HttpClient.HttpResponse.
     */
    private byte[] buildRawHttpResponse(HttpResponse<byte[]> httpResponse) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // Status Line: HTTP/1.1 <statusCode> <reasonPhrase>\r\n
        String statusLine = "HTTP/1.1 " + httpResponse.statusCode() + " " + getReasonPhrase(httpResponse.statusCode()) + "\r\n";
        bos.write(statusLine.getBytes(StandardCharsets.ISO_8859_1));

        // Headers
        httpResponse.headers().map().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Transfer-Encoding") && !name.equalsIgnoreCase("Connection") && !name.equalsIgnoreCase("Keep-Alive") && !name.equalsIgnoreCase("Content-Length")) {
                values.forEach(value -> {
                    try {
                        bos.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    } catch (IOException e) {
                        log.error("Error writing response header: {}", e.getMessage());
                    }
                });
            }
        });

        byte[] body = httpResponse.body();
        if (body != null && body.length > 0) {
            bos.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }

        // End of headers
        bos.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));

        // Body
        if (body != null && body.length > 0) {
            bos.write(body);
        }
        return bos.toByteArray();
    }

    /**
     * Simple helper to get a reason phrase for common HTTP status codes.
     */
    private String getReasonPhrase(int statusCode) {
        return switch (statusCode) {
            case 100 -> "Continue";
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Unknown Status";
        };
    }

    /**
     * Creates a simple HTTP error response body.
     */
    private byte[] createErrorResponse(int statusCode, String statusText, String message) throws IOException {
        String htmlBody = String.format("<html><body><h1>%d %s</h1><p>%s</p></body></html>", statusCode, statusText, message);
        String response = String.format("HTTP/1.1 %d %s\r\n" + "Content-Type: text/html\r\n" + "Content-Length: %d\r\n" + "Connection: close\r\n" + "\r\n" + "%s", statusCode, statusText, htmlBody.getBytes(StandardCharsets.ISO_8859_1).length, htmlBody);
        return response.getBytes(StandardCharsets.ISO_8859_1);
    }
}