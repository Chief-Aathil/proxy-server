package com.proxy.server.protocol;

public final class ProxyProtocolConstants {

    // Message Types (from proxy-client to proxy-server)
    public static final byte MSG_TYPE_HTTP_REQUEST = 0x01; // For forwarding original HTTP requests (after tunnel established)
    public static final byte MSG_TYPE_CONNECT_REQUEST = 0x02; // For requesting a tunnel to a target host/port

    // Message Types (from proxy-server to proxy-client - responses)
    public static final byte MSG_TYPE_CONNECT_SUCCESS = 0x10; // Proxy-server confirms tunnel established
    public static final byte MSG_TYPE_CONNECT_FAILED = 0x11; // Proxy-server failed to connect to origin
    public static final byte MSG_TYPE_HTTP_RESPONSE = 0x12; // Raw HTTP response from origin server

    // Common framing headers
    // Length for host string in CONNECT_REQUEST. Using short for 2 bytes, max 32767 chars, which is sufficient.
    public static final int HOST_LENGTH_BYTES = 2; // Short type length
    public static final int PORT_LENGTH_BYTES = 4; // Int type length (for port number)
    public static final int CONTENT_LENGTH_BYTES = 8; // Long type length (for request/response bodies)


    // Private constructor to prevent instantiation
    private ProxyProtocolConstants() {}
}