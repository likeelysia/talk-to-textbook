package io.github.likeelysia.formalagent.config;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;

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
 * <p><b>CSRF 关闭</b>:这是同源 SPA + JSON 接口,登录态用 SameSite=Lax 的会话 Cookie;
 * 如果以后要接第三方站点再打开 CSRF token。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService users(
            @Value("${fa.auth.username:pengge}") String username,
            @Value("${fa.auth.password:}") String password,
            @Value("${fa.auth.password-hash:}") String passwordHash,
            PasswordEncoder encoder) {
        String hash = passwordHash == null ? "" : passwordHash.strip();
        if (hash.isBlank() && password != null && !password.isBlank()) {
            hash = encoder.encode(password);
        }
        if (hash.isBlank()) {
            throw new IllegalStateException(
                    "未配置登录凭据:请设置 FA_AUTH_PASSWORD_HASH(推荐)或 FA_AUTH_PASSWORD,再启动");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username == null || username.isBlank() ? "pengge" : username.strip())
                        .password(hash)
                        .roles("USER")
                        .build());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 首页/登录/静态欢迎资源/健康检查:公开
                        .requestMatchers("/", "/index.html", "/welcome.css", "/welcome.js",
                                "/contact.html", "/login.html", "/favicon.ico",
                                "/api/health", "/error").permitAll()
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
                // 同源 SPA + JSON:关闭 CSRF;登录态靠 SameSite=Lax 的会话 Cookie
                .csrf(csrf -> csrf.disable())
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
