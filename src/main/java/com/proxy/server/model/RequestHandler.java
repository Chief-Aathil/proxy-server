package com.proxy.server.model;


import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

public interface RequestHandler {
    boolean supports(String method); // HTTP/S method
    void handle(ParsedRequest request, Socket clientSocket, OutputStream out) throws IOException;
}

