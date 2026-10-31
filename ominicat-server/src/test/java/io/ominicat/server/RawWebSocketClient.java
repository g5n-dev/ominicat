package io.ominicat.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Real TCP peer, including a mode that never reads after upgrade. */
final class RawWebSocketClient implements AutoCloseable {
    final Socket socket;
    final int status;

    RawWebSocketClient(int port) throws IOException { this(port, ""); }

    RawWebSocketClient(int port, String extraHeaders) throws IOException {
        socket = new Socket();
        try {
            socket.setReceiveBufferSize(1024);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(3000);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            String key = Base64.getEncoder().encodeToString(new byte[16]);
            String request = "GET /ws HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
                    + key + "\r\n" + extraHeaders + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            StringBuilder headers = new StringBuilder();
            while (headers.length() < 16_384 && !headers.toString().endsWith("\r\n\r\n")) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new EOFException("Upgrade response ended early");
                headers.append((char) value);
            }
            status = Integer.parseInt(headers.substring(0, headers.indexOf("\r\n")).split(" ")[1]);
        } catch (IOException | RuntimeException failure) {
            try { socket.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void text(String text) throws IOException { frame(1, text.getBytes(StandardCharsets.UTF_8)); }

    synchronized void frame(int opcode, byte[] payload) throws IOException {
        var output = socket.getOutputStream();
        output.write(0x80 | opcode);
        if (payload.length < 126) output.write(0x80 | payload.length);
        else if (payload.length <= 65535) {
            output.write(0x80 | 126); output.write(payload.length >>> 8); output.write(payload.length);
        } else {
            output.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) output.write((int) ((long) payload.length >>> shift));
        }
        byte[] mask = {1, 2, 3, 4}; output.write(mask);
        byte[] masked = new byte[payload.length];
        for (int index = 0; index < payload.length; index++) masked[index] = (byte) (payload[index] ^ mask[index % 4]);
        output.write(masked); output.flush();
    }

    Message read() throws IOException {
        InputStream input = socket.getInputStream();
        int first = input.read(), second = input.read();
        if (first < 0 || second < 0) throw new EOFException("Socket closed");
        long size = second & 127;
        if (size == 126) size = ByteBuffer.wrap(exact(input, 2)).getShort() & 65535;
        else if (size == 127) size = ByteBuffer.wrap(exact(input, 8)).getLong();
        if ((second & 128) != 0 || size < 0 || size > 16 * 1024 * 1024) throw new IOException("Invalid server frame");
        return new Message(first & 15, exact(input, (int) size));
    }

    private static byte[] exact(InputStream input, int size) throws IOException {
        byte[] result = input.readNBytes(size);
        if (result.length != size) throw new EOFException("Incomplete frame");
        return result;
    }

    record Message(int opcode, byte[] payload) {
        String text() { return new String(payload, StandardCharsets.UTF_8); }
        int closeCode() { return payload.length < 2 ? -1 : ByteBuffer.wrap(payload).getShort() & 65535; }
    }

    @Override public void close() throws IOException { socket.close(); }
}
