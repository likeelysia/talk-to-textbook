package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    @Test
    void blocksAfterFailuresAndRecoversAfterBackoff() {
        AtomicLong now = new AtomicLong(1_000_000L);
        LoginAttemptService service = new LoginAttemptService(now::get);

        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            service.failure("1.2.3.4", "pengge");
        }
        assertThat(service.blocked("1.2.3.4", "pengge")).isFalse();

        service.failure("1.2.3.4", "pengge");
        assertThat(service.blocked("1.2.3.4", "pengge")).isTrue();
        assertThat(service.blocked("9.9.9.9", "pengge")).isTrue();       // 账号维度,换 IP 也没用
        assertThat(service.blockedForSeconds("1.2.3.4", "pengge")).isGreaterThan(0);

        now.addAndGet(LoginAttemptService.BASE_BLOCK_MS + 1);
        assertThat(service.blocked("1.2.3.4", "pengge")).isFalse();
    }

    @Test
    void successClearsAccountAndIp() {
        LoginAttemptService service = new LoginAttemptService(() -> 1_000_000L);
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.failure("1.2.3.4", "pengge");
        }
        assertThat(service.blocked("1.2.3.4", "pengge")).isTrue();

        service.success("1.2.3.4", "pengge");
        assertThat(service.blocked("1.2.3.4", "pengge")).isFalse();
    }
}
