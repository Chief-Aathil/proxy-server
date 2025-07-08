package com.proxy.server.http;

import com.proxy.server.model.ParsedRequest;
import com.proxy.server.model.RequestHandler;
import com.proxy.server.utils.RequestUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class HttpRequestHandler implements RequestHandler {

    @Override
    public boolean supports(String method) {
        return !"CONNECT".equalsIgnoreCase(method);
    }

    @Override
    public void handle(ParsedRequest request, Socket clientSocket, OutputStream out) {
        try {
            String url = buildFullUrl(request.headers(), request.path());
            HttpResponse<byte[]> response = sendHttpRequestBytes(request.method(), url, request.headers());
            writeRawHttpResponse(response, out);
        } catch (Exception e) {
            try {
                out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n".getBytes());
                out.flush();
            } catch (IOException ignored) {}
        }
    }

    private String buildFullUrl(Map<String, String> headers, String path) throws IllegalArgumentException {
        String host = headers.get("Host");
        if (host == null) throw new IllegalArgumentException("Host header is missing");
        return "http://" + host + path;
    }

    private HttpResponse<byte[]> sendHttpRequestBytes(String method, String url, Map<String, String> headers) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase("Host")) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void writeRawHttpResponse(HttpResponse<byte[]> response, OutputStream out) throws IOException {
        StringBuilder headersBuilder = new StringBuilder();

        // Status line
        headersBuilder.append("HTTP/1.1 ")
                .append(response.statusCode())
                .append(" ")
                .append(RequestUtils.getReasonPhrase(response.statusCode()))
                .append("\r\n");

        // Filter and copy headers
        appendFilteredHeaders(headersBuilder, response);

        // Overwrite Content-Length and Connection
        headersBuilder.append("Content-Length: ").append(response.body().length).append("\r\n");
        headersBuilder.append("Connection: close\r\n");
        headersBuilder.append("\r\n");

        // Write headers + body
        System.out.println("response headers:\n"+headersBuilder.toString());
        out.write(headersBuilder.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(response.body());
        out.flush();
    }

    private void appendFilteredHeaders(StringBuilder headersBuilder, HttpResponse<byte[]> response) {
        response.headers().map().forEach((key, values) -> {
            if (key == null) return;
            String lowerKey = key.toLowerCase();
            if (lowerKey.equals("transfer-encoding") || lowerKey.equals("content-length") || lowerKey.equals("connection")) {
                return;
            }
            for (String value : values) {
                headersBuilder.append(key).append(": ").append(value).append("\r\n");
            }
        });
    }
}

