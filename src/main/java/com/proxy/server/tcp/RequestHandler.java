package com.proxy.server.tcp;


import com.proxy.server.model.ParsedRequest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;

public interface RequestHandler {
    boolean supports(String method); // e.g., "GET", "CONNECT"
    void handle(ParsedRequest request, Socket clientSocket, BufferedWriter out) throws IOException;
}
