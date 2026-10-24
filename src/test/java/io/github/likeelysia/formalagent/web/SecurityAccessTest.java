package io.github.likeelysia.formalagent.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 访问保护验收:未登录不能进学习页/业务 API;登录后可以。 */
@SpringBootTest(properties = {
        "fa.cli.enabled=false",
        "fa.auth.username=pengge",
        "fa.auth.password=test-password"
})
@AutoConfigureMockMvc
@DisplayName("单用户访问保护")
class SecurityAccessTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("未登录:首页/健康公开,学习页跳登录,业务 API 401")
    void unauthenticatedIsBlockedExceptPublicPages() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/health")).andExpect(status().isOk());

        mvc.perform(get("/study.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login.html")));

        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/memory")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vocab/book")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/vocab/explain").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        // 联系页公开时,它依赖的样式和二维码也必须放行
        mvc.perform(get("/contact.html")).andExpect(status().isOk());
        mvc.perform(get("/style.css")).andExpect(status().isOk());
        mvc.perform(get("/study-theme.css")).andExpect(status().isOk());
        mvc.perform(get("/img/wechat.jpg")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("登录后:能读登录状态和记忆接口")
    void loginGrantsAccess() throws Exception {
        MvcResult login = mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "pengge")
                        .param("password", "test-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/study.html"))
                .andReturn();

        HttpSession session = login.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("pengge"))
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    @DisplayName("连续错误口令被登录入口限流,换新会话也绕不过")
    void loginBruteForceIsThrottled() throws Exception {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            mvc.perform(post("/login")
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("10.9.9.9");
                                return request;
                            })
                            .param("username", "attacker")
                            .param("password", "wrong-password"))
                    .andExpect(status().is3xxRedirection());
        }

        mvc.perform(post("/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("10.9.9.9");
                            return request;
                        })
                        .param("username", "attacker")
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("login.html?locked=1")));
    }

    @Test
    @DisplayName("状态变更请求缺 CSRF 被拒;带 CSRF 可退出登录")
    void stateChangingRequestsRequireCsrf() throws Exception {
        MvcResult login = mvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "pengge")
                        .param("password", "test-password"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        org.springframework.mock.web.MockHttpSession session =
                (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void vocabularyWritesAndModelCallsRequireCsrf() throws Exception {
        for (String path : new String[]{"/api/vocab/extract", "/api/vocab/docx", "/api/vocab/book", "/api/vocab/explain"}) {
            mvc.perform(post(path).with(user("pengge")).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
    }
}
