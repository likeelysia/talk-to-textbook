package io.github.likeelysia.formalagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.agent.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 通过 Streamable HTTP 调用配置好的远程 MCP 搜索工具。 */
@Component
public class WebSearchTool implements Tool {

    private final ObjectMapper mapper;
    private final String url;
    private final String token;
    private final String remoteTool;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public WebSearchTool(ObjectMapper mapper, @Value("${FA_WEB_MCP_URL:}") String url,
                         @Value("${FA_WEB_MCP_TOKEN:}") String token,
                         @Value("${FA_WEB_MCP_TOOL:web_search}") String remoteTool,
                         @Value("${FA_TAVILY_API_KEY:}") String tavilyKey) {
        this.mapper = mapper;
        // 显式配置远程地址时，使用该服务自己的工具名与令牌。
        boolean useTavily = url.isBlank() && !tavilyKey.isBlank();
        this.url = useTavily ? "https://mcp.tavily.com/mcp/" : url.strip();
        this.token = useTavily ? tavilyKey.strip() : token.strip();
        this.remoteTool = useTavily ? "tavily_search" : remoteTool.strip();
    }

    @Override
    public String name() { return "search_web"; }

    public boolean configured() { return !url.isBlank(); }

    @Override
    public String description() {
        return "仅在教材检索无法回答本轮问题时，使用简短的公开关键词搜索网页。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "required", List.of("query"),
                "properties", Map.of("query", Map.of("type", "string")));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String query = Tool.strArg(args, "query").strip();
        if (query.isBlank() || query.length() > 240) return "联网搜索未执行：关键词须为 1 到 240 字。";
        if (url.isBlank()) return "联网搜索未配置。请说明目前只能使用已有资料。";
        String session = "";
        String version = "2025-11-25";
        try {
            URI endpoint = URI.create(url);
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null) return "联网搜索配置错误：请使用 HTTPS MCP 地址。";

            var init = post(endpoint, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of("protocolVersion", version, "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "Lamplit", "version", "1"))), "", "");
            session = init.headers().firstValue("Mcp-Session-Id").orElse("");
            version = result(init, 1).path("protocolVersion").asText();
            if (!List.of("2025-11-25", "2025-06-18", "2025-03-26").contains(version)) {
                return "联网搜索不可用：MCP 协议版本不兼容。";
            }
            var ready = post(endpoint, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"),
                    session, version);
            if (ready.statusCode() != 202) return "联网搜索不可用：MCP 初始化失败。";
            JsonNode found = result(post(endpoint, Map.of("jsonrpc", "2.0", "id", 2,
                    "method", "tools/call", "params", Map.of("name", remoteTool,
                            "arguments", Map.of("query", query))), session, version), 2);
            if (found.path("isError").asBoolean()) return "联网搜索失败。请说明未获得网页依据。";
            JsonNode structured = found.path("structuredContent");
            if (structured.isObject()) return structured.toString();
            StringBuilder evidence = new StringBuilder();
            for (JsonNode item : found.path("content")) {
                if ("text".equals(item.path("type").asText())) {
                    evidence.append(item.path("text").asText()).append('\n');
                }
                if (evidence.length() >= 6000) break;
            }
            return evidence.isEmpty() ? "联网搜索未返回文字依据。" : evidence.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "联网搜索已中断。";
        } catch (Exception e) {
            return "联网搜索超时或不可用。请说明未获得网页依据。";
        } finally {
            if (!session.isBlank()) closeSession(session, version);
        }
    }

    private HttpRequest.Builder request(URI endpoint, String session, String version) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(4)).header("Accept", "application/json, text/event-stream");
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        if (!session.isBlank()) request.header("Mcp-Session-Id", session);
        if (!version.isBlank()) request.header("MCP-Protocol-Version", version);
        return request;
    }

    private HttpResponse<String> post(URI endpoint, Map<String, Object> message,
                                      String session, String version) throws Exception {
        var pending = http.sendAsync(request(endpoint, session, version).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(message))).build(),
                HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(4, TimeUnit.SECONDS);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private void closeSession(String session, String version) {
        http.sendAsync(request(URI.create(url), session, version).DELETE().build(),
                HttpResponse.BodyHandlers.discarding()).exceptionally(error -> null);
    }

    private JsonNode result(HttpResponse<String> response, int id) throws Exception {
        if (response.statusCode() != 200) throw new IllegalStateException("MCP HTTP error");
        String type = response.headers().firstValue("Content-Type").orElse("");
        if (type.contains("text/event-stream")) {
            // SSE 的行分隔符只包括 CR 和 LF。正文里的 Unicode 分隔符仍属于 JSON。
            String body = response.body().replace("\r\n", "\n").replace('\r', '\n');
            for (String event : body.split("\n\n")) {
                StringBuilder data = new StringBuilder();
                for (String line : event.split("\n")) {
                    if (line.startsWith("data:")) data.append(line.substring(5)).append('\n');
                }
                if (data.isEmpty()) continue;
                JsonNode message = mapper.readTree(data.toString());
                if (message != null && message.path("id").asInt(-1) == id) return readResult(message, id);
            }
            throw new IllegalStateException("MCP response missing");
        }
        return readResult(mapper.readTree(response.body()), id);
    }

    private JsonNode readResult(JsonNode message, int id) {
        if (message == null || message.path("id").asInt(-1) != id || !message.path("result").isObject()) {
            throw new IllegalStateException("MCP result invalid");
        }
        return message.path("result");
    }
}
