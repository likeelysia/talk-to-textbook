package io.github.likeelysia.formalagent.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按账号记录每天/每月的 AI 调用用量，并保存管理员设置的额度。
 *
 * <p>数据量很小（一个账号一天一行），用 JSON 而不是再引入一套数据库；
 * 所有读写同步，避免并发请求把文件写坏。写盘先写临时文件再原子替换。
 */
@Component
public class UsageStore {

    private static final Logger log = LoggerFactory.getLogger(UsageStore.class);

    public record Usage(long requests, long inputTokens, long outputTokens, long costMicros) {
        public static final Usage ZERO = new Usage(0, 0, 0, 0);
        public Usage plus(long units, long input, long output, long cost) {
            return new Usage(requests + units, inputTokens + input, outputTokens + output, costMicros + cost);
        }
    }

    public record Quota(int dailyRequests, int monthlyRequests) { }

    private final ObjectMapper mapper;
    private final Path file;
    private final int defaultDaily;
    private final int defaultMonthly;
    private final int globalMonthly;
    private final ObjectNode root;

    public UsageStore(ObjectMapper mapper,
                      @Value("${fa.usage.file:data/usage/usage.json}") String file,
                      @Value("${fa.billing.default-daily-requests:60}") int defaultDaily,
                      @Value("${fa.billing.default-monthly-requests:600}") int defaultMonthly,
                      @Value("${fa.billing.global-monthly-requests:5000}") int globalMonthly) {
        this.mapper = mapper;
        this.file = Path.of(file == null || file.isBlank() ? "data/usage/usage.json" : file)
                .toAbsolutePath().normalize();
        this.defaultDaily = Math.max(0, defaultDaily);
        this.defaultMonthly = Math.max(0, defaultMonthly);
        this.globalMonthly = Math.max(0, globalMonthly);
        this.root = load();
    }

    public String today() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    public String month() {
        return today().substring(0, 7);
    }

    public Quota quota(String userId) {
        synchronized (this) {
            JsonNode q = root.path("quotas").path(userId);
            return new Quota(q.path("dailyRequests").asInt(defaultDaily),
                    q.path("monthlyRequests").asInt(defaultMonthly));
        }
    }

    public void setQuota(String userId, int dailyRequests, int monthlyRequests) {
        if (dailyRequests < 0 || monthlyRequests < 0) throw new AgentException("额度不能为负数");
        if (dailyRequests > 100000 || monthlyRequests > 1000000) throw new AgentException("额度设置过大");
        synchronized (this) {
            ObjectNode quotas = (ObjectNode) root.with("quotas");
            ObjectNode q = quotas.with(userId);
            q.put("dailyRequests", dailyRequests);
            q.put("monthlyRequests", monthlyRequests);
            write();
        }
    }

    public Usage usage(String userId, String day) {
        synchronized (this) {
            JsonNode n = root.path("days").path(day).path(userId);
            return new Usage(n.path("requests").asLong(0), n.path("inputTokens").asLong(0),
                    n.path("outputTokens").asLong(0), n.path("costMicros").asLong(0));
        }
    }

    public Usage todayUsage(String userId) {
        return usage(userId, today());
    }

    public Usage monthUsage(String userId) {
        synchronized (this) {
            String prefix = month();
            Usage total = Usage.ZERO;
            JsonNode days = root.path("days");
            var dayNames = days.fieldNames();
            while (dayNames.hasNext()) {
                String day = dayNames.next();
                if (!day.startsWith(prefix)) continue;
                JsonNode n = days.path(day).path(userId);
                total = total.plus(n.path("requests").asLong(0), n.path("inputTokens").asLong(0),
                        n.path("outputTokens").asLong(0), n.path("costMicros").asLong(0));
            }
            return total;
        }
    }

    public long globalMonthRequests() {
        synchronized (this) {
            String prefix = month();
            long total = 0;
            JsonNode days = root.path("days");
            var dayNames = days.fieldNames();
            while (dayNames.hasNext()) {
                String day = dayNames.next();
                if (!day.startsWith(prefix)) continue;
                var users = days.path(day).elements();
                while (users.hasNext()) total += users.next().path("requests").asLong(0);
            }
            return total;
        }
    }

    public void add(String userId, long units, long inputTokens, long outputTokens, long costMicros) {
        synchronized (this) {
            ObjectNode days = (ObjectNode) root.with("days");
            ObjectNode day = days.with(today());
            ObjectNode n = day.with(userId);
            n.put("requests", n.path("requests").asLong(0) + units);
            n.put("inputTokens", n.path("inputTokens").asLong(0) + inputTokens);
            n.put("outputTokens", n.path("outputTokens").asLong(0) + outputTokens);
            n.put("costMicros", n.path("costMicros").asLong(0) + costMicros);
            write();
        }
    }

    public int defaultsDaily() { return defaultDaily; }
    public int defaultsMonthly() { return defaultMonthly; }
    public int globalMonthly() { return globalMonthly; }
    public String file() { return file.toString(); }

    private ObjectNode load() {
        if (!Files.exists(file)) {
            ObjectNode fresh = mapper.createObjectNode();
            fresh.put("version", 1);
            fresh.set("quotas", mapper.createObjectNode());
            fresh.set("days", mapper.createObjectNode());
            return fresh;
        }
        try {
            JsonNode parsed = mapper.readTree(Files.readString(file));
            if (parsed == null || !parsed.isObject()) throw new IOException("格式不正确");
            ObjectNode node = (ObjectNode) parsed;
            if (!node.path("quotas").isObject()) node.set("quotas", mapper.createObjectNode());
            if (!node.path("days").isObject()) node.set("days", mapper.createObjectNode());
            return node;
        } catch (Exception e) {
            throw new AgentException("用量数据读取失败，原文件已保留；请检查或从备份恢复", e);
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("用量数据写入失败:{}", file, e);
            throw new AgentException("用量数据保存失败:" + e.getMessage(), e);
        }
    }

    public Map<String, Object> view(String userId) {
        Quota q = quota(userId);
        Usage today = todayUsage(userId);
        Usage month = monthUsage(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dailyRequests", q.dailyRequests());
        body.put("monthlyRequests", q.monthlyRequests());
        body.put("todayRequests", today.requests());
        body.put("monthRequests", month.requests());
        body.put("todayInputTokens", today.inputTokens());
        body.put("todayOutputTokens", today.outputTokens());
        body.put("monthCostMicros", month.costMicros());
        return body;
    }
}
