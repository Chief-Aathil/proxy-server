package com.proxy.server.tcp;

import com.proxy.server.model.ParsedRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class RequestParser {

    public ParsedRequest parse(String request) {
        String[] lines = request.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new IllegalArgumentException("Empty request line");
        }

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            throw new IllegalArgumentException("Malformed request line: " + lines[0]);
        }

        String method = requestLine[0];
        String path = requestLine[1];

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;

            int colonIndex = line.indexOf(":");
            if (colonIndex > 0) {
                String name = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(name, value);
            }
        }

        return new ParsedRequest(method, path, headers);
    }
}
