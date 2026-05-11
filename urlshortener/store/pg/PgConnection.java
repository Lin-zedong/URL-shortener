package urlshortener.store.pg;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Минимальный PostgreSQL wire-клиент без сторонних зависимостей.
 * Поддерживает trust/no-auth, cleartext, md5 и SCRAM-SHA-256.
 */
public final class PgConnection implements Closeable {

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final String user;
    private final String password;
    private ScramSession scramSession;

    public PgConnection(String host, int port, String database, String user, String password, int timeoutMillis) {
        try {
            this.user = user;
            this.password = password == null ? "" : password;
            this.socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(Math.max(timeoutMillis, 30_000));
            this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            startup(database);
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось открыть соединение с PostgreSQL", ex);
        }
    }

    private void startup(String database) throws IOException {
        ByteArrayOutputStream params = new ByteArrayOutputStream();
        DataOutputStream buffer = new DataOutputStream(params);
        writeCString(buffer, "user");
        writeCString(buffer, user);
        writeCString(buffer, "database");
        writeCString(buffer, database);
        writeCString(buffer, "client_encoding");
        writeCString(buffer, "UTF8");
        writeCString(buffer, "DateStyle");
        writeCString(buffer, "ISO, MDY");
        buffer.writeByte(0);
        buffer.flush();

        int length = 4 + 4 + params.size();
        output.writeInt(length);
        output.writeInt(196608);
        output.write(params.toByteArray());
        output.flush();

        boolean ready = false;
        while (!ready) {
            byte type = input.readByte();
            int messageLength = input.readInt();
            byte[] payload = input.readNBytes(messageLength - 4);
            switch (type) {
                case 'R' -> handleAuthentication(payload);
                case 'S', 'K' -> {
                    // ParameterStatus и BackendKeyData не требуются приложению.
                }
                case 'Z' -> ready = true;
                case 'N' -> {
                    // NoticeResponse можно безопасно игнорировать.
                }
                case 'E' -> throw new IllegalStateException(parseError(payload));
                default -> {
                    // Остальные сообщения на этапе старта не используются.
                }
            }
        }
    }

    private void handleAuthentication(byte[] payload) throws IOException {
        DataInputStream auth = new DataInputStream(new ByteArrayInputStream(payload));
        int authCode = auth.readInt();
        switch (authCode) {
            case 0 -> {
                return;
            }
            case 3 -> sendPasswordMessage(password);
            case 5 -> {
                byte[] salt = auth.readNBytes(4);
                sendPasswordMessage(md5Password(password, user, salt));
            }
            case 10 -> startScramAuthentication(Arrays.copyOfRange(payload, 4, payload.length));
            case 11 -> continueScramAuthentication(new String(Arrays.copyOfRange(payload, 4, payload.length), StandardCharsets.UTF_8));
            case 12 -> finishScramAuthentication(new String(Arrays.copyOfRange(payload, 4, payload.length), StandardCharsets.UTF_8));
            default -> throw new IllegalStateException("Неподдерживаемый режим аутентификации PostgreSQL: " + authCode);
        }
    }

    private void startScramAuthentication(byte[] mechanismsPayload) throws IOException {
        List<String> mechanisms = readCStringList(mechanismsPayload);
        if (!mechanisms.contains("SCRAM-SHA-256")) {
            throw new IllegalStateException("PostgreSQL предложил неподдерживаемые SASL-механизмы: " + mechanisms);
        }
        if (password.isEmpty()) {
            throw new IllegalStateException("PostgreSQL требует SCRAM-SHA-256, но пароль БД не задан. Укажите APP_DB_PASSWORD или используйте локальную БД с trust-аутентификацией.");
        }
        scramSession = ScramSession.start(user);
        sendSaslInitialResponse("SCRAM-SHA-256", scramSession.clientFirstMessage());
    }

    private void continueScramAuthentication(String serverFirstMessage) throws IOException {
        if (scramSession == null) {
            throw new IllegalStateException("Получен SCRAM-challenge без начального SCRAM-сообщения");
        }
        sendSaslResponse(scramSession.clientFinalMessage(serverFirstMessage, password));
    }

    private void finishScramAuthentication(String serverFinalMessage) {
        if (scramSession == null) {
            throw new IllegalStateException("Получен финальный SCRAM-ответ без активной SCRAM-сессии");
        }
        scramSession.verifyServerFinalMessage(serverFinalMessage);
        scramSession = null;
    }

    private void sendPasswordMessage(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeByte('p');
        output.writeInt(4 + bytes.length + 1);
        output.write(bytes);
        output.writeByte(0);
        output.flush();
    }

    private void sendSaslInitialResponse(String mechanism, String initialResponse) throws IOException {
        byte[] mechanismBytes = mechanism.getBytes(StandardCharsets.UTF_8);
        byte[] responseBytes = initialResponse.getBytes(StandardCharsets.UTF_8);
        output.writeByte('p');
        output.writeInt(4 + mechanismBytes.length + 1 + 4 + responseBytes.length);
        output.write(mechanismBytes);
        output.writeByte(0);
        output.writeInt(responseBytes.length);
        output.write(responseBytes);
        output.flush();
    }

    private void sendSaslResponse(String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        output.writeByte('p');
        output.writeInt(4 + responseBytes.length);
        output.write(responseBytes);
        output.flush();
    }

    public PgQueryResult execute(String sql) {
        try {
            byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
            output.writeByte('Q');
            output.writeInt(4 + sqlBytes.length + 1);
            output.write(sqlBytes);
            output.writeByte(0);
            output.flush();

            List<String> columns = List.of();
            List<List<String>> rows = new ArrayList<>();
            String commandTag = null;
            boolean ready = false;
            while (!ready) {
                byte type = input.readByte();
                int messageLength = input.readInt();
                byte[] payload = input.readNBytes(messageLength - 4);
                switch (type) {
                    case 'T' -> columns = parseRowDescription(payload);
                    case 'D' -> rows.add(parseDataRow(payload));
                    case 'C' -> commandTag = parseCommandTag(payload);
                    case 'Z' -> ready = true;
                    case 'N' -> {
                        // NoticeResponse игнорируется.
                    }
                    case 'E' -> throw new IllegalStateException(parseError(payload) + " | SQL=" + sql);
                    default -> {
                        // Остальные сообщения не нужны для текущего прототипа.
                    }
                }
            }
            return new PgQueryResult(columns, rows, commandTag);
        } catch (IOException ex) {
            throw new IllegalStateException("Ошибка работы с PostgreSQL", ex);
        }
    }

    public void begin() {
        execute("BEGIN");
    }

    public void commit() {
        execute("COMMIT");
    }

    public void rollback() {
        try {
            execute("ROLLBACK");
        } catch (Exception ignored) {
            // Ошибка rollback не должна скрывать исходную причину.
        }
    }

    private List<String> parseRowDescription(byte[] payload) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload));
        int fieldCount = data.readUnsignedShort();
        List<String> columns = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            columns.add(readCString(data));
            data.skipBytes(4 + 2 + 4 + 2 + 4 + 2);
        }
        return columns;
    }

    private List<String> parseDataRow(byte[] payload) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload));
        int fieldCount = data.readUnsignedShort();
        List<String> row = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            int len = data.readInt();
            if (len < 0) {
                row.add(null);
            } else {
                byte[] bytes = data.readNBytes(len);
                row.add(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return row;
    }

    private String parseCommandTag(byte[] payload) {
        return new String(payload, 0, Math.max(0, payload.length - 1), StandardCharsets.UTF_8);
    }

    private String parseError(byte[] payload) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload));
        String sqlState = null;
        String message = null;
        while (true) {
            int code = data.readUnsignedByte();
            if (code == 0) {
                break;
            }
            String value = readCString(data);
            if (code == 'C') {
                sqlState = value;
            } else if (code == 'M') {
                message = value;
            }
        }
        if (sqlState == null && message == null) {
            return "Неизвестная ошибка PostgreSQL";
        }
        if (sqlState == null) {
            return message;
        }
        if (message == null) {
            return sqlState;
        }
        return sqlState + ": " + message;
    }

    private static void writeCString(DataOutputStream out, String value) throws IOException {
        if (value != null && !value.isEmpty()) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
        out.writeByte(0);
    }

    private static String readCString(DataInputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int next = in.readUnsignedByte();
            if (next == 0) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
            buffer.write(next);
        }
    }

    private static List<String> readCStringList(byte[] payload) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload));
        List<String> values = new ArrayList<>();
        while (data.available() > 0) {
            String value = readCString(data);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String md5Password(String password, String user, byte[] salt) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] first = md5.digest((password + user).getBytes(StandardCharsets.UTF_8));
            StringBuilder firstHex = new StringBuilder();
            for (byte b : first) {
                firstHex.append(String.format("%02x", b));
            }
            md5.reset();
            md5.update(firstHex.toString().getBytes(StandardCharsets.UTF_8));
            md5.update(salt);
            byte[] second = md5.digest();
            StringBuilder result = new StringBuilder("md5");
            for (byte b : second) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 недоступен в JVM", ex);
        }
    }

    @Override
    public void close() {
        try {
            output.writeByte('X');
            output.writeInt(4);
            output.flush();
        } catch (Exception ignored) {
            // Сокет всё равно будет закрыт ниже.
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Игнорируем ошибки при освобождении сокета.
        }
    }

    private static final class ScramSession {
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
        private static final Base64.Encoder NONCE_ENCODER = Base64.getEncoder().withoutPadding();
        private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

        private final String clientNonce;
        private final String clientFirstBare;
        private String expectedServerSignature;

        private ScramSession(String clientNonce, String clientFirstBare) {
            this.clientNonce = clientNonce;
            this.clientFirstBare = clientFirstBare;
        }

        static ScramSession start(String user) {
            byte[] nonceBytes = new byte[18];
            RANDOM.nextBytes(nonceBytes);
            String nonce = NONCE_ENCODER.encodeToString(nonceBytes);
            String clientFirstBare = "n=" + escapeSaslName(user) + ",r=" + nonce;
            return new ScramSession(nonce, clientFirstBare);
        }

        String clientFirstMessage() {
            return "n,," + clientFirstBare;
        }

        String clientFinalMessage(String serverFirstMessage, String password) {
            try {
                Map<String, String> attrs = parseScramAttributes(serverFirstMessage);
                String serverNonce = attrs.get("r");
                String saltBase64 = attrs.get("s");
                String iterationText = attrs.get("i");
                if (serverNonce == null || saltBase64 == null || iterationText == null) {
                    throw new IllegalStateException("Некорректный SCRAM-challenge от PostgreSQL: " + serverFirstMessage);
                }
                if (!serverNonce.startsWith(clientNonce)) {
                    throw new IllegalStateException("SCRAM nonce PostgreSQL не соответствует клиентскому nonce");
                }
                int iterations = Integer.parseInt(iterationText);
                byte[] salt = BASE64_DECODER.decode(saltBase64);
                byte[] saltedPassword = hi(password, salt, iterations);

                String clientFinalWithoutProof = "c=biws,r=" + serverNonce;
                String authMessage = clientFirstBare + "," + serverFirstMessage + "," + clientFinalWithoutProof;

                byte[] clientKey = hmac(saltedPassword, "Client Key");
                byte[] storedKey = sha256(clientKey);
                byte[] clientSignature = hmac(storedKey, authMessage);
                byte[] clientProof = xor(clientKey, clientSignature);

                byte[] serverKey = hmac(saltedPassword, "Server Key");
                byte[] serverSignature = hmac(serverKey, authMessage);
                expectedServerSignature = BASE64_ENCODER.encodeToString(serverSignature);

                return clientFinalWithoutProof + ",p=" + BASE64_ENCODER.encodeToString(clientProof);
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Не удалось сформировать SCRAM-ответ PostgreSQL", ex);
            }
        }

        void verifyServerFinalMessage(String serverFinalMessage) {
            Map<String, String> attrs = parseScramAttributes(serverFinalMessage);
            String error = attrs.get("e");
            if (error != null) {
                throw new IllegalStateException("PostgreSQL отклонил SCRAM-аутентификацию: " + error);
            }
            String verifier = attrs.get("v");
            if (verifier == null) {
                throw new IllegalStateException("PostgreSQL не вернул SCRAM server verifier");
            }
            if (!MessageDigest.isEqual(verifier.getBytes(StandardCharsets.UTF_8), expectedServerSignature.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalStateException("SCRAM server verifier PostgreSQL не совпадает с ожидаемым значением");
            }
        }

        private static String escapeSaslName(String value) {
            return value.replace("=", "=3D").replace(",", "=2C");
        }

        private static Map<String, String> parseScramAttributes(String value) {
            Map<String, String> attrs = new LinkedHashMap<>();
            for (String part : value.split(",")) {
                int separator = part.indexOf('=');
                if (separator > 0) {
                    attrs.put(part.substring(0, separator), part.substring(separator + 1));
                }
            }
            return attrs;
        }

        private static byte[] hi(String password, byte[] salt, int iterations) throws GeneralSecurityException {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        }

        private static byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        }

        private static byte[] sha256(byte[] value) throws GeneralSecurityException {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }

        private static byte[] xor(byte[] first, byte[] second) {
            byte[] result = new byte[first.length];
            for (int i = 0; i < first.length; i++) {
                result[i] = (byte) (first[i] ^ second[i]);
            }
            return result;
        }
    }
}
