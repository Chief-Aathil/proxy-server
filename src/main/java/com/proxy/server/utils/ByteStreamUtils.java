package com.proxy.server.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ByteStreamUtils {

    /**
     * Reads exactly 'length' bytes from the InputStream into the buffer, starting at offset.
     * Throws IOException if end of stream is reached prematurely.
     */
    public static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int bytesRead = 0;
        while (bytesRead < length) {
            int result = in.read(buffer, offset + bytesRead, length - bytesRead);
            if (result == -1) {
                throw new IOException("Reached end of stream prematurely. Expected " + length + " bytes, read " + bytesRead);
            }
            bytesRead += result;
        }
    }

    /**
     * Reads a long value from the InputStream.
     * Assumes big-endian byte order (standard for DataInputStream).
     */
    public static long readLong(InputStream in) throws IOException {
        byte[] buffer = new byte[8];
        readFully(in, buffer, 0, 8);
        return ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    /**
     * Reads an int value from the InputStream.
     * Assumes big-endian byte order (standard for DataInputStream).
     */
    public static int readInt(InputStream in) throws IOException {
        byte[] buffer = new byte[4];
        readFully(in, buffer, 0, 4);
        return ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN).getInt();
    }
}