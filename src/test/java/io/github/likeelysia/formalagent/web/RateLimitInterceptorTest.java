package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
}
