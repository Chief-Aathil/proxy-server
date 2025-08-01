package com.proxy.server.communicator;

import lombok.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Custom byte-based framing protocol for messages exchanged over the tunnel.
 * Protocol: MessageType (1 byte), RequestID (16 bytes), PayloadLength (4 bytes), PayloadBytes (variable).
 */
@Getter
@RequiredArgsConstructor
@EqualsAndHashCode
@ToString(exclude = "payload")
public class FramedMessage {

    @Getter
    @RequiredArgsConstructor
    public enum MessageType {
        HTTP_REQUEST((byte) 1),
        HTTP_RESPONSE((byte) 2),
        HTTPS_CONNECT((byte) 3),
        HTTPS_DATA((byte) 4),
        CONTROL_200_OK((byte) 5),
        CONTROL_TUNNEL_CLOSE((byte) 6),
        CONTROL_500_ERROR((byte) 50),
        HEARTBEAT_PING((byte) 7),
        HEARTBEAT_PONG((byte) 8),
        UNKNOWN((byte) 0);

        private final byte value;

        public static MessageType fromByte(byte b) {
            for (MessageType type : MessageType.values()) {
                if (type.value == b) {
                    return type;
                }
            }
            return UNKNOWN;
        }
    }

    @NonNull private final MessageType messageType;
    @NonNull private final UUID requestID;
    @NonNull private final byte[] payload;

    public byte[] toBytes() throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.writeByte(messageType.getValue());
            dos.writeLong(requestID.getMostSignificantBits());
            dos.writeLong(requestID.getLeastSignificantBits());
            dos.writeInt(payload.length);
            dos.write(payload);
            dos.flush();
            return bos.toByteArray();
        }
    }

    public static FramedMessage fromStream(DataInputStream dis) throws IOException {
        byte typeValue = dis.readByte();
        MessageType messageType = MessageType.fromByte(typeValue);
        if (messageType == MessageType.UNKNOWN) {
            throw new IOException("Unknown message type: " + typeValue);
        }

        long mostSigBits = dis.readLong();
        long leastSigBits = dis.readLong();
        UUID requestID = new UUID(mostSigBits, leastSigBits);

        int payloadLength = dis.readInt();
        if (payloadLength < 0) {
            throw new IOException("Invalid payload length: " + payloadLength);
        }

        byte[] payload = new byte[payloadLength];
        dis.readFully(payload);

        return new FramedMessage(messageType, requestID, payload);
    }
}