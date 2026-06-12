import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class Backend {
    private static final int PORT = 8080;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String DB_URL = "jdbc:mysql://localhost:3306/igreja?useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", new RootHandler());

        server.setExecutor(null);
        server.start();

        System.out.println("Backend Java rodando em http://localhost:" + PORT);
        System.out.println("Abra http://localhost:" + PORT + " para visualizar o site.");
    }

    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            if (path.startsWith("/api/")) {
                handleApi(exchange);
                return;
            }

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendOptions(exchange);
                return;
            }

            if ("/".equals(path)) {
                path = "/site.html";
            }

            Path file = resolveStaticFile(path);
            if (Files.exists(file) && Files.isRegularFile(file)) {
                serveFile(exchange, file);
                return;
            }

            String body = "<h1>404</h1><p>Página não encontrada.</p>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(404, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        private void handleApi(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if ("/api/health".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 200, "{\"status\":\"ok\",\"message\":\"Backend Java ativo\"}");
                return;
            }

            if ("/api/oracao".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> form = readForm(exchange.getRequestBody());
                String nome = clean(form.get("nome"));
                String email = clean(form.get("email"));
                String mensagem = clean(form.get("mensagem"));

                if (nome.isEmpty() || email.isEmpty() || mensagem.isEmpty()) {
                    writeJson(exchange, 400, "{\"success\":false,\"message\":\"Preencha nome, e-mail e pedido de oração.\"}");
                    return;
                }

                try {
                    salvarNoBanco(nome, email, mensagem);
                    writeJson(exchange, 200, "{\"success\":true,\"message\":\"Pedido de oração recebido com sucesso!\"}");
                } catch (SQLException ex) {
                    salvarEmArquivo(nome, email, mensagem);
                    writeJson(exchange, 200, "{\"success\":true,\"message\":\"Pedido registrado no banco e em backup local.\"}");
                }
                return;
            }

            if ("/api/admin/login".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> form = readForm(exchange.getRequestBody());
                String email = clean(form.get("email"));
                String senha = clean(form.get("senha"));

                if (email.isEmpty() || senha.isEmpty()) {
                    writeJson(exchange, 400, "{\"success\":false,\"message\":\"Informe e-mail e senha.\"}");
                    return;
                }

                try {
                    boolean valido = validarLoginAdmin(email, senha);
                    if (valido) {
                        writeJson(exchange, 200, "{\"success\":true,\"message\":\"Login autorizado.\"}");
                    } else {
                        writeJson(exchange, 401, "{\"success\":false,\"message\":\"E-mail ou senha inválidos.\"}");
                    }
                } catch (SQLException ex) {
                    writeJson(exchange, 500, "{\"success\":false,\"message\":\"Erro ao acessar o banco.\"}");
                }
                return;
            }

            if ("/api/admin/pedidos".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    String json = listarPedidosJson();
                    writeJson(exchange, 200, json);
                } catch (SQLException ex) {
                    writeJson(exchange, 500, "{\"success\":false,\"message\":\"Erro ao carregar pedidos.\"}");
                }
                return;
            }

            if (path.startsWith("/api/admin/pedidos/") && path.endsWith("/aprovar") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    int id = extrairId(path);
                    aprovarPedido(id);
                    writeJson(exchange, 200, "{\"success\":true,\"message\":\"Pedido aprovado com sucesso.\"}");
                } catch (Exception ex) {
                    writeJson(exchange, 400, "{\"success\":false,\"message\":\"Não foi possível aprovar o pedido.\"}");
                }
                return;
            }

            if (path.startsWith("/api/admin/pedidos/") && path.endsWith("/responder") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    int id = extrairId(path);
                    Map<String, String> form = readForm(exchange.getRequestBody());
                    String resposta = clean(form.get("resposta"));

                    if (resposta.isEmpty()) {
                        writeJson(exchange, 400, "{\"success\":false,\"message\":\"Digite uma resposta para o pedido.\"}");
                        return;
                    }

                    salvarResposta(id, resposta);
                    writeJson(exchange, 200, "{\"success\":true,\"message\":\"Resposta enviada com sucesso.\"}");
                } catch (Exception ex) {
                    writeJson(exchange, 400, "{\"success\":false,\"message\":\"Não foi possível salvar a resposta.\"}");
                }
                return;
            }

            if (path.startsWith("/api/admin/pedidos/") && path.endsWith("/excluir") && "DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    int id = extrairId(path);
                    excluirPedido(id);
                    writeJson(exchange, 200, "{\"success\":true,\"message\":\"Pedido excluído com sucesso.\"}");
                } catch (Exception ex) {
                    writeJson(exchange, 400, "{\"success\":false,\"message\":\"Não foi possível excluir o pedido.\"}");
                }
                return;
            }

            writeJson(exchange, 404, "{\"success\":false,\"message\":\"Endpoint não encontrado.\"}");
        }

        private static Map<String, String> readForm(InputStream inputStream) throws IOException {
            String body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();

            if (body.isBlank()) {
                return values;
            }

            for (String part : body.split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length > 1
                        ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                        : "";
                values.put(key, value);
            }

            return values;
        }

        private static void salvarNoBanco(String nome, String email, String mensagem) throws SQLException {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("Driver JDBC do MySQL não foi encontrado.", ex);
            }

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                int proximoId = 1;
                try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(id), 0) + 1 FROM testemunho")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            proximoId = rs.getInt(1);
                        }
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO testemunho (id, usuario_id, titulo, testemunho, data_envio, status_tes, oracao, categoria, anonimo) " +
                        "VALUES (?, ?, ?, ?, NOW(), ?, ?, ?, ?)");
                ) {
                    ps.setInt(1, proximoId);
                    ps.setNull(2, Types.INTEGER);
                    ps.setString(3, "Pedido de oração");
                    ps.setString(4, "Nome: " + nome + "\nE-mail: " + email + "\n\nPedido: " + mensagem);
                    ps.setBoolean(5, false);
                    ps.setString(6, mensagem);
                    ps.setString(7, "Oração");
                    ps.setBoolean(8, false);
                    ps.executeUpdate();
                }
            }
        }

        private static void salvarEmArquivo(String nome, String email, String mensagem) throws IOException {
            Path file = Paths.get("back", "pedidos-oracao.txt").toAbsolutePath().normalize();
            Files.createDirectories(file.getParent());

            String registro = String.format("%s | nome=%s | email=%s | mensagem=%s%n",
                    LocalDateTime.now().format(TIMESTAMP_FORMAT),
                    nome,
                    email,
                    mensagem);

            Files.writeString(file, registro, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private static boolean validarLoginAdmin(String email, String senha) throws SQLException {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("Driver JDBC do MySQL não foi encontrado.", ex);
            }

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement("SELECT id FROM usuario WHERE email = ? AND senha = ? AND adm = 1 LIMIT 1")) {
                ps.setString(1, email);
                ps.setString(2, senha);

                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        private static int extrairId(String path) {
            String[] partes = path.split("/");
            return Integer.parseInt(partes[4]);
        }

        private static void aprovarPedido(int id) throws SQLException {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("Driver JDBC do MySQL não foi encontrado.", ex);
            }

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement("UPDATE testemunho SET status_tes = 1 WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }

        private static void salvarResposta(int id, String resposta) throws SQLException {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("Driver JDBC do MySQL não foi encontrado.", ex);
            }

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement("UPDATE testemunho SET resposta = ?, status_tes = 1 WHERE id = ?")) {
                ps.setString(1, resposta);
                ps.setInt(2, id);
                ps.executeUpdate();

                // Buscar o e-mail do solicitante para enviar a resposta
                String solicitanteEmail = null;
                String pedidoOracao = null;
                try (PreparedStatement psSelect = conn.prepareStatement("SELECT testemunho, oracao FROM testemunho WHERE id = ?")) {
                    psSelect.setInt(1, id);
                    try (ResultSet rs = psSelect.executeQuery()) {
                        if (rs.next()) {
                            String testemunhoCompleto = rs.getString("testemunho");
                            solicitanteEmail = extrairCampo(testemunhoCompleto, "E-mail:");
                            pedidoOracao = rs.getString("oracao");
                        }
                    }
                }

                if (solicitanteEmail != null && !solicitanteEmail.isEmpty()) {
                    System.out.println("Resposta registrada para: " + solicitanteEmail);
                }
            }
        }

        private static String escapeHtml(String value) {
            if (value == null) {
                return "";
            }
            return value
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#x27;");
        }

        private static void excluirPedido(int id) throws SQLException {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("Driver JDBC do MySQL não foi encontrado.", ex);
            }

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM testemunho WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        }

        private static String listarPedidosJson() throws SQLException {
            try {
                Class.forName("com.mysql.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("Driver JDBC do MySQL não foi encontrado.", ex);
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"success\":true,\"total\":");

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM testemunho");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                json.append(rs.getInt(1));
            }

            json.append(",\"pedidos\":[");

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement("SELECT id, titulo, oracao, categoria, DATE_FORMAT(data_envio, '%d/%m/%Y %H:%i:%s') AS data_envio, testemunho, status_tes, resposta FROM testemunho ORDER BY id DESC")) {
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        String testemunho = rs.getString("testemunho");
                        String nome = extrairCampo(testemunho, "Nome:");
                        String email = extrairCampo(testemunho, "E-mail:");

                        if (!first) {
                            json.append(',');
                        }
                        first = false;
                        json.append("{\"id\":").append(rs.getInt("id"))
                                .append(",\"titulo\":\"").append(escapeJson(rs.getString("titulo")))
                                .append("\",\"email\":\"").append(escapeJson(email))
                                .append("\",\"nome\":\"").append(escapeJson(nome))
                                .append("\",\"pedido\":\"").append(escapeJson(rs.getString("oracao")))
                                .append("\",\"categoria\":\"").append(escapeJson(rs.getString("categoria")))
                                .append("\",\"data_envio\":\"").append(escapeJson(rs.getString("data_envio")))
                                .append("\",\"status_tes\":").append(rs.getBoolean("status_tes") ? "true" : "false")
                                .append(",\"resposta\":\"").append(escapeJson(rs.getString("resposta")))
                                .append("\"}");
                    }
                }
            }

            json.append("]}");
            return json.toString();
        }

        private static String extrairCampo(String texto, String prefixo) {
            if (texto == null || prefixo == null) {
                return "";
            }

            int inicio = texto.indexOf(prefixo);
            if (inicio < 0) {
                return "";
            }

            inicio += prefixo.length();
            int fim = texto.indexOf('\n', inicio);
            if (fim < 0) {
                fim = texto.length();
            }

            return texto.substring(inicio, fim).trim();
        }

        private static String escapeJson(String value) {
            if (value == null) {
                return "";
            }
            return value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        private static void serveFile(HttpExchange exchange, Path file) throws IOException {
            byte[] content = Files.readAllBytes(file);
            String mimeType = mimeTypeFor(file.getFileName().toString());

            exchange.getResponseHeaders().set("Content-Type", mimeType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            addCorsHeaders(exchange);

            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(content);
            }
        }

        private static void sendOptions(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
            exchange.sendResponseHeaders(204, -1);
        }

        private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        private static void addCorsHeaders(HttpExchange exchange) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        }

        private static Path resolveStaticFile(String path) {
            String normalized = path;
            if ("/".equals(normalized)) {
                normalized = "/site.html";
            }

            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }

            Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
            Path frontDir = projectRoot.resolve("front").normalize();
            Path target = frontDir.resolve(normalized).normalize();

            if (!target.startsWith(frontDir)) {
                return frontDir.resolve("site.html");
            }

            return target;
        }

        private static String mimeTypeFor(String fileName) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
            if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
            if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }
}

