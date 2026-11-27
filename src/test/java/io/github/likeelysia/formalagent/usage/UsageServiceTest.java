package io.github.likeelysia.formalagent.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageServiceTest {
    @TempDir Path temp;

    @Test void enforcesDailyQuotaAndRecordsUsage() {
        var store = new UsageStore(new ObjectMapper(), temp.resolve("usage.json").toString(), 2, 10, 100);
        var service = new UsageService(store, 100, 0, 0);
        service.check("u1", false, 1);
        service.record("u1", 1, "a bank", "银行");
        service.check("u1", false, 1);
        service.record("u1", 1, "a bank", "银行");
        assertThat(store.todayUsage("u1").requests()).isEqualTo(2);
        assertThatThrownBy(() -> service.check("u1", false, 1))
                .hasMessageContaining("今日");
    }

    @Test void adminAndZeroQuotaAreUnlimited() {
        var store = new UsageStore(new ObjectMapper(), temp.resolve("usage.json").toString(), 1, 1, 1);
        var service = new UsageService(store, 100, 0, 0);
        service.record("u1", 5, "x", "y");
        service.check("u1", true, 10);
        service.setQuota("u1", 0, 0);
        service.check("u1", false, 10);
        assertThat(store.quota("u1").dailyRequests()).isZero();
    }
}
