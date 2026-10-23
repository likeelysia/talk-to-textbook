package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitInterceptorTest {

    @Test
    void rejectsAfterLimitWithinWindow() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(2);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent");
        request.getSession(true);

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletResponse third = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, third, new Object())).isFalse();
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getContentAsString()).contains("请求太频繁");
    }

    @Test
    void limitIsByAccountNotSession() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("pengge", "", List.of()));
        try {
            RateLimitInterceptor interceptor = new RateLimitInterceptor(2);
            MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/agent");
            first.getSession(true);
            assertThat(interceptor.preHandle(first, new MockHttpServletResponse(), new Object())).isTrue();
            assertThat(interceptor.preHandle(first, new MockHttpServletResponse(), new Object())).isTrue();

            // 模拟退出再登录得到的新会话:账号相同,仍然应该被拦
            MockHttpServletRequest newSession = new MockHttpServletRequest("POST", "/api/agent");
            newSession.getSession(true);
            MockHttpServletResponse third = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(newSession, third, new Object())).isFalse();
            assertThat(third.getStatus()).isEqualTo(429);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
