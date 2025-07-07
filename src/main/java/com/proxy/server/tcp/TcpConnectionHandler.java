package com.proxy.server.tcp;

import com.proxy.server.http.HttpRequestHandler;
import com.proxy.server.https.HttpsRequestHandler;
import com.proxy.server.model.ParsedRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;

@Component
public class TcpConnectionHandler implements Runnable {

    @Value("${proxy.server.port}")
    private int port;

    private final HttpRequestHandler httpRequestHandler;
    private final HttpsRequestHandler httpsRequestHandler;
    private final RequestParser requestParser;

    @Autowired
    public TcpConnectionHandler(HttpRequestHandler httpHandler, HttpsRequestHandler httpsHandler, RequestParser requestParser) {
        this.httpRequestHandler = httpHandler;
        this.httpsRequestHandler = httpsHandler;
        this.requestParser = requestParser;
    }

    @PostConstruct
    public void init() {
        new Thread(this).start(); // Start server socket in new thread
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[proxy-server] Listening on port " + port + "...");

            Socket clientSocket = serverSocket.accept();
            System.out.println("[proxy-server] Connection established with proxy-client!");

            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));

            while (!clientSocket.isClosed()) {
                handleRequest(clientSocket, in, out);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleRequest(Socket clientSocket, BufferedReader in, BufferedWriter out) throws IOException {
        while (!clientSocket.isClosed()) {
            StringBuilder request = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) break;
                request.append(line).append("\r\n");
            }

            if (request.length() == 0) continue;

            try {
                ParsedRequest parsed = requestParser.parse(request.toString());

                if ("CONNECT".equalsIgnoreCase(parsed.method())) {
                    httpsRequestHandler.handle(parsed, clientSocket, out);
                } else {
                    httpRequestHandler.handle(parsed, clientSocket, out);
                }
            } catch (Exception e) {
                System.err.println("[proxy-server] Error handling request: " + e.getMessage());
                out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n");
                out.flush();
            }
        }
    }


}
