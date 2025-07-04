package com.proxy.server.tcp;

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

            String line;
            StringBuilder request = new StringBuilder();
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    // End of headers — respond with mock data
                    System.out.println("[proxy-server] Full request:\n" + request);

                    String mockResponse = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nHello";
                    out.write(mockResponse);
                    out.flush();

                    // Clear request buffer for next one
                    request.setLength(0);
                } else {
                    request.append(line).append("\r\n");
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
