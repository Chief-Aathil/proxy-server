package com.proxy.server.utils;

import java.io.IOException;
import java.io.InputStream;

public class RequestUtils {
    public static String readHttpRequestHeader(InputStream in) throws IOException {
        StringBuilder headerBuilder = new StringBuilder();
        int prev = -1, curr;

        while ((curr = in.read()) != -1) {
            headerBuilder.append((char) curr);

            if (prev == '\r' && curr == '\n') {
                int len = headerBuilder.length();
                if (len >= 4 &&
                        headerBuilder.charAt(len - 4) == '\r' &&
                        headerBuilder.charAt(len - 3) == '\n' &&
                        headerBuilder.charAt(len - 2) == '\r' &&
                        headerBuilder.charAt(len - 1) == '\n') {
                    break; // End of headers
                }
            }

            prev = curr;
        }

        return headerBuilder.toString();
    }

    public static String getReasonPhrase(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> ""; // Fallback for unknown codes
        };
    }



}
