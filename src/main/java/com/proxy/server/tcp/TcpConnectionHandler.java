package com.proxy.server.tcp;

import com.proxy.server.http.HttpRequestHandler;
import com.proxy.server.https.HttpsRequestHandler;
import com.proxy.server.model.ParsedRequest;
import com.proxy.server.utils.RequestUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        new Thread(this).start();
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            //Establish connection with proxy-client
            System.out.println("[proxy-server] Listening on port " + port + "...");
            Socket clientSocket = serverSocket.accept();
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();
            System.out.println("[proxy-server] Connection established with proxy-client!");

            while (!clientSocket.isClosed()) {
                handleRequest(clientSocket, in, out);
            }
            System.out.println("Connection to proxy-client closed!!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleRequest(Socket clientSocket, InputStream in, OutputStream out) throws IOException {
        String requestHeader = RequestUtils.readHttpRequestHeader(in);
        if (requestHeader.isEmpty()) {
            System.out.println("Request header empty..");
            return;
        }

        try {
            ParsedRequest parsedRequest = requestParser.parse(requestHeader);
            if (isHttpsRequest(parsedRequest)) {
                httpsRequestHandler.handle(parsedRequest, clientSocket, out);
            } else {
                httpRequestHandler.handle(parsedRequest, clientSocket, out);
            }
        } catch (Exception e) {
            System.err.println("[proxy-server] Error handling request: " + e.getMessage());
            out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n".getBytes());
            out.flush();
        }
    }

    private static boolean isHttpsRequest(ParsedRequest parsedRequest) {
        return "CONNECT".equalsIgnoreCase(parsedRequest.method());
    }
}
