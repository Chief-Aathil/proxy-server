package com.proxy.server.http;

import com.proxy.server.model.ParsedRequest;
import com.proxy.server.tcp.RequestHandler;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Component
public class HttpRequestHandler implements RequestHandler {

    @Override
    public boolean supports(String method) {
        return !"CONNECT".equalsIgnoreCase(method); // we support everything *except* CONNECT
    }

    @Override
    public void handle(ParsedRequest request, Socket clientSocket, BufferedWriter out) {
        try {
            String url = buildFullUrl(request.headers(), request.path());
            var response = sendHttpRequest(request.method(), url, request.headers());
            String httpResponse = buildHttpResponse(response);

            out.write(httpResponse);
            out.flush();
        } catch (Exception e) {
            try {
                out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n");
                out.flush();
            } catch (IOException ignored) {}
        }
    }

    private ParsedRequest parseHttpRequest(String request) {
        String[] lines = request.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new IllegalArgumentException("Empty request line");
        }

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 3) {
            throw new IllegalArgumentException("Malformed request line: " + lines[0]);
        }

        String method = requestLine[0];
        String path = requestLine[1];

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            int colon = line.indexOf(":");
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
            }
        }

        return new ParsedRequest(method, path, headers);
    }


    private String buildFullUrl(Map<String, String> headers, String path) throws IllegalArgumentException {
        String host = headers.get("Host");
        if (host == null) throw new IllegalArgumentException("Host header is missing");
        return "http://" + host + path;
    }

    private HttpResponse<String> sendHttpRequest(String method, String url, Map<String, String> headers) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url));

        if ("GET".equalsIgnoreCase(method)) {
            builder.GET();
        } else {
            throw new UnsupportedOperationException("Method not supported: " + method);
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase("Host")) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String buildHttpResponse(HttpResponse<String> response) {
        StringBuilder raw = new StringBuilder();
        raw.append("HTTP/1.1 ").append(response.statusCode()).append(" OK\r\n");

        response.headers().map().forEach((key, values) -> {
            for (String value : values) {
                raw.append(key).append(": ").append(value).append("\r\n");
            }
        });

        byte[] bodyBytes = response.body().getBytes();
        raw.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        raw.append("\r\n");
        raw.append(response.body());

        return raw.toString();
    }

    private String handleHttpRequest(String requestString) {
        try {
            ParsedRequest parsed = parseHttpRequest(requestString);
            String url = buildFullUrl(parsed.headers(), parsed.path());
            HttpResponse<String> response = sendHttpRequest(parsed.method(), url, parsed.headers());
            return buildHttpResponse(response);
        } catch (IllegalArgumentException e) {
            return makeErrorResponse(400, e.getMessage());
        } catch (UnsupportedOperationException e) {
            return makeErrorResponse(501, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return makeErrorResponse(500, "Internal Server Error");
        }
    }

    private String makeErrorResponse(int code, String message) {
        return "HTTP/1.1 " + code + " " + message + "\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";
    }
}
