package io.github.likeelysia.formalagent.config;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import io.github.likeelysia.formalagent.web.AccountStatusFilter;
import io.github.likeelysia.formalagent.web.CsrfCookieFilter;
import io.github.likeelysia.formalagent.web.UserAccessFilter;

/**
 * 单用户受控访问:登录后才能看学习数据、调模型;首页/登录页/健康检查公开。
 *
 * <p><b>为什么用 Spring Security</b>:认证、会话、密码哈希、登录跳转都是成熟实现,
 * 不自己发明加密算法,也不靠前端隐藏按钮。
 *
 * <p><b>凭据从环境变量来</b>:
 * <ul>
 *   <li>{@code FA_AUTH_USERNAME}(默认 pengge);</li>
 *   <li>{@code FA_AUTH_PASSWORD_HASH}:BCrypt 哈希(推荐);</li>
 *   <li>{@code FA_AUTH_PASSWORD}:明文口令(只在启动时编码进内存,不落盘;适合本地演练)。</li>
 * </ul>
 * 两个都没配 = 启动直接失败,避免"以为有登录、其实裸奔"。
 *
 * <p><b>CSRF 开启</b>:Session 认证下用 Spring Security 的 CSRF 保护;登录/退出表单带
 * {@code _csrf} 隐藏字段,SPA 写请求/上传/SSE 带 {@code X-XSRF-TOKEN}(Cookie {@code XSRF-TOKEN})。
 * 不是靠 SameSite 代替 CSRF。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 这两个过滤器只挂在 Spring Security 链上；关掉 Servlet 容器的自动注册，
     * 否则同一个请求会被执行两次。
     */
    @Bean
    public FilterRegistrationBean<AccountStatusFilter> accountStatusFilterRegistration(
            AccountStatusFilter filter) {
        FilterRegistrationBean<AccountStatusFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<UserAccessFilter> userAccessFilterRegistration(
            UserAccessFilter filter) {
        FilterRegistrationBean<UserAccessFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AccountStatusFilter accountStatusFilter,
                                                   UserAccessFilter userAccessFilter) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 首页/登录/静态欢迎资源/健康检查:公开
                        .requestMatchers("/", "/index.html", "/welcome.css", "/welcome.js",
                                "/contact.html", "/login.html", "/favicon.ico",
                                "/style.css", "/study-theme.css", "/img/wechat.jpg",
                                "/api/health", "/api/auth/csrf", "/error").permitAll()
                        // 其余(study.html、app.js、vocab.html、所有业务 API)都要登录
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/study.html", true)
                        .failureUrl("/login.html?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                // Session 认证 + 浏览器表单/SSE/上传:恢复框架 CSRF;
                // SPA 读 XSRF-TOKEN Cookie,写操作带 X-XSRF-TOKEN 头
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // SPA 从 Cookie 取原始 token 写 X-XSRF-TOKEN;不要用 XOR 掩码
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                // API 未登录返回 401(让前端跳登录),页面未登录仍跳登录页
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .defaultAccessDeniedHandlerFor(
                                (request, response, denied) -> {
                                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                    response.setContentType("application/json;charset=UTF-8");
                                    response.getWriter().write("{\"error\":\"没有权限\"}");
                                },
                                request -> request.getRequestURI().startsWith("/api/")))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        http.addFilterAfter(accountStatusFilter, AnonymousAuthenticationFilter.class);
        http.addFilterAfter(userAccessFilter, AccountStatusFilter.class);
        return http.build();
    }

    private static org.springframework.security.web.AuthenticationEntryPoint authenticationEntryPoint() {
        RequestMatcher api = request -> request.getRequestURI().startsWith("/api/");
        DelegatingAuthenticationEntryPoint entry = new DelegatingAuthenticationEntryPoint(
                new LinkedHashMap<>(Map.of(api, new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))));
        entry.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login.html"));
        return entry;
    }
}
