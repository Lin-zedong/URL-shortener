package urlshortener.service.redis;

import urlshortener.config.AppConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Минимальный RESP-клиент Redis без внешних библиотек.
 */
public final class RedisClient {

    private final String host;
    private final int port;
    private final String password;
    private final int databaseIndex;
    private final int timeoutMillis;

    public RedisClient(String host, int port, String password, int databaseIndex, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.databaseIndex = databaseIndex;
        this.timeoutMillis = timeoutMillis;
    }

    public RedisNode command(String... args) {
        try (Session session = openSession(timeoutMillis)) {
            return session.command(args);
        } catch (IOException ex) {
            throw new IllegalStateException("Ошибка подключения к Redis", ex);
        }
    }

    public RedisNode command(int commandTimeoutMillis, String... args) {
        try (Session session = openSession(commandTimeoutMillis)) {
            return session.command(args);
        } catch (IOException ex) {
            throw new IllegalStateException("Ошибка подключения к Redis", ex);
        }
    }

    private Session openSession(int commandTimeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setSoTimeout(Math.max(timeoutMillis, commandTimeoutMillis + 1000));
        Session session = new Session(socket);
        if (!password.isBlank()) {
            session.command("AUTH", password);
        }
        if (databaseIndex > 0) {
            session.command("SELECT", String.valueOf(databaseIndex));
        }
        return session;
    }

    private static final class Session implements Closeable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;

        private Session(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        private RedisNode command(String... args) throws IOException {
            writeCommand(args);
            RedisNode reply = readReply();
            if (reply.type() == RedisNode.Type.SIMPLE_STRING || reply.type() == RedisNode.Type.BULK_STRING
                    || reply.type() == RedisNode.Type.INTEGER || reply.type() == RedisNode.Type.ARRAY
                    || reply.type() == RedisNode.Type.NULL) {
                return reply;
            }
            return reply;
        }

        private void writeCommand(String... args) throws IOException {
            output.write(('*' + String.valueOf(args.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String arg : args) {
                byte[] bytes = (arg == null ? "" : arg).getBytes(StandardCharsets.UTF_8);
                output.write(('$' + String.valueOf(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(bytes);
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
        }

        private RedisNode readReply() throws IOException {
            int marker = input.read();
            if (marker < 0) {
                throw new IOException("Redis закрыл соединение");
            }
            return switch ((char) marker) {
                case '+' -> RedisNode.simple(readLine());
                case ':' -> RedisNode.integer(Long.parseLong(readLine()));
                case '$' -> readBulkString();
                case '*' -> readArray();
                case '-' -> throw new IllegalStateException("Redis error: " + readLine());
                default -> throw new IOException("Неожиданный RESP-маркер Redis: " + (char) marker);
            };
        }

        private RedisNode readBulkString() throws IOException {
            int length = Integer.parseInt(readLine());
            if (length < 0) {
                return RedisNode.nil();
            }
            byte[] bytes = input.readNBytes(length);
            readCrlf();
            return RedisNode.bulk(new String(bytes, StandardCharsets.UTF_8));
        }

        private RedisNode readArray() throws IOException {
            int count = Integer.parseInt(readLine());
            if (count < 0) {
                return RedisNode.nil();
            }
            List<RedisNode> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(readReply());
            }
            return RedisNode.array(values);
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
                int next = input.read();
                if (next < 0) {
                    throw new IOException("Неожиданный конец потока Redis");
                }
                if (next == '\r') {
                    int lf = input.read();
                    if (lf != '\n') {
                        throw new IOException("Нарушен формат RESP-строки Redis");
                    }
                    return buffer.toString(StandardCharsets.UTF_8);
                }
                buffer.write(next);
            }
        }

        private void readCrlf() throws IOException {
            int cr = input.read();
            int lf = input.read();
            if (cr != '\r' || lf != '\n') {
                throw new IOException("Нарушен формат RESP-блока Redis");
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
