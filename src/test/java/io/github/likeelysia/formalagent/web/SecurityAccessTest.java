package io.github.likeelysia.formalagent.web;

import static org.hamcrest.Matchers.containsString;
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
    }

    @Test
    @DisplayName("登录后:能读登录状态和记忆接口")
    void loginGrantsAccess() throws Exception {
        MvcResult login = mvc.perform(post("/login")
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
}
