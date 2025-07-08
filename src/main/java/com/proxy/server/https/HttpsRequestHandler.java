package com.proxy.server.https;

import com.proxy.server.model.ParsedRequest;
import com.proxy.server.model.RequestHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

@Component
public class HttpsRequestHandler  implements RequestHandler {
    @Override
    public boolean supports(String method) {
        return "CONNECT".equalsIgnoreCase(method);
    }

    @Override
    public void handle(ParsedRequest request, Socket clientSocket, OutputStream out) throws IOException {
        String[] hostParts = request.path().split(":");
        if (hostParts.length != 2) {
            out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes());
            out.flush();
            return;
        }

        String host = hostParts[0];
        int port = Integer.parseInt(hostParts[1]);

        try (Socket targetSocket = new Socket(host, port)) {
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
            out.flush();
            forwardStreams(clientSocket, targetSocket);
        } catch (IOException e) {
            System.err.println("HTTPS Tunnel failed: " + e.getMessage());
        }
    }

    private void forwardStreams(Socket clientSocket, Socket targetSocket) {
        new Thread(() -> pipe(clientSocket, targetSocket)).start();
        new Thread(() -> pipe(targetSocket, clientSocket)).start();
    }

    private void pipe(Socket inSocket, Socket outSocket) {
        try (
                InputStream in = inSocket.getInputStream();
                OutputStream out = outSocket.getOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {}
    }
}
