package com.proxy.server.tcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
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
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[proxy-server] Received: " + line);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
