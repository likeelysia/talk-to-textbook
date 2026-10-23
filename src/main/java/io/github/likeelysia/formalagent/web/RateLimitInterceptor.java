package io.github.likeelysia.formalagent.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 收费模型接口的简单限流:默认每个会话/IP 每分钟最多 12 次问答。
 *
 * <p>单用户场景够用;多用户要换成按用户/租户的令牌桶,并把计数放到共享存储。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MS = 60_000L;

    private final int perMinute;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimitInterceptor(@Value("${fa.rate-limit.per-minute:12}") int perMinute) {
        this.perMinute = Math.max(1, perMinute);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String key = keyOf(request);
        long now = System.currentTimeMillis();
        Deque<Long> bucket = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && now - bucket.peekFirst() > WINDOW_MS) {
                bucket.pollFirst();
            }
            if (bucket.size() >= perMinute) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"请求太频繁,请稍后再试(每分钟 "
                        + perMinute + " 次)\"}");
                return false;
            }
            bucket.addLast(now);
        }
        return true;
    }

    private static String keyOf(HttpServletRequest request) {
        String session = request.getSession(false) == null ? null : request.getSession(false).getId();
        return session != null ? "s:" + session : "ip:" + request.getRemoteAddr();
    }
}
