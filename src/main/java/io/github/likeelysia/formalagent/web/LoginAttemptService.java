package io.github.likeelysia.formalagent.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 登录失败退避(按来源地址 + 账号 + 全局)。
 *
 * <p>不依赖会话 Cookie —— 换新 Cookie 不能把计数清零;成功登录才清对应地址/账号。
 * 退避是短时的(30 秒起,最多 15 分钟),不永久锁死唯一账号。
 */
@Component
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    static final int MAX_FAILURES = 5;
    static final int GLOBAL_MAX_FAILURES = 20;
    static final long WINDOW_MS = 5 * 60_000L;
    static final long BASE_BLOCK_MS = 30_000L;
    static final long MAX_BLOCK_MS = 15 * 60_000L;
    private static final int MAX_TRACKED_KEYS = 2_000;

    private static final class State {
        int failures;
        long windowStart;
        long blockedUntil;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public LoginAttemptService() {
        this(System::currentTimeMillis);
    }

    /** 测试用:注入可控时钟。 */
    LoginAttemptService(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean blocked(String ip, String username) {
        return blockedForSeconds(ip, username) > 0;
    }

    /** 还要等多少秒;0 = 未锁。 */
    public long blockedForSeconds(String ip, String username) {
        long now = clock.getAsLong();
        long until = 0;
        for (String key : keys(ip, username)) {
            State state = states.get(key);
            if (state == null) {
                continue;
            }
            synchronized (state) {
                if (state.blockedUntil > now) {
                    until = Math.max(until, state.blockedUntil);
                } else if (state.blockedUntil > 0) {
                    state.blockedUntil = 0;
                }
            }
        }
        return until <= now ? 0 : Math.max(1, (until - now + 999) / 1000);
    }

    public void failure(String ip, String username) {
        long now = clock.getAsLong();
        record("ip:" + safe(ip), MAX_FAILURES, now);
        record("user:" + safe(username), MAX_FAILURES, now);
        record("global", GLOBAL_MAX_FAILURES, now);
        cleanup(now);
    }

    public void success(String ip, String username) {
        states.remove("ip:" + safe(ip));
        states.remove("user:" + safe(username));
        states.remove("global");           // 单用户:成功登录后允许立刻恢复,避免全局锁死
    }

    private void record(String key, int limit, long now) {
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            if (state.windowStart == 0 || now - state.windowStart > WINDOW_MS) {
                state.failures = 0;
                state.windowStart = now;
                state.blockedUntil = 0;
            }
            if (state.blockedUntil > now) {
                return;
            }
            state.failures++;
            if (state.failures >= limit) {
                long multiplier = 1L << Math.min(state.failures - limit, 5);
                long block = Math.min(BASE_BLOCK_MS * multiplier, MAX_BLOCK_MS);
                state.blockedUntil = now + block;
                log.warn("登录失败次数过多(key={}),退避 {} 秒", key, block / 1000);
            }
        }
    }

    private void cleanup(long now) {
        if (states.size() <= MAX_TRACKED_KEYS) {
            return;
        }
        states.entrySet().removeIf(e -> {
            State state = e.getValue();
            synchronized (state) {
                return state.blockedUntil <= now && now - state.windowStart > WINDOW_MS;
            }
        });
    }

    private static String[] keys(String ip, String username) {
        return new String[] {"ip:" + safe(ip), "user:" + safe(username), "global"};
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
