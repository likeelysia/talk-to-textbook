package io.github.likeelysia.formalagent.usage;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AI 调用的配额检查、估算和记账。管理员不受额度限制。 */
@Component
public class UsageService {

    private final UsageStore store;
    private final int globalMonthly;
    private final long inputMicrosPerMillion;
    private final long outputMicrosPerMillion;

    public UsageService(UsageStore store,
                        @Value("${fa.billing.global-monthly-requests:5000}") int globalMonthly,
                        @Value("${fa.billing.input-cents-per-million:0}") long inputCentsPerMillion,
                        @Value("${fa.billing.output-cents-per-million:0}") long outputCentsPerMillion) {
        this.store = store;
        this.globalMonthly = Math.max(0, globalMonthly);
        this.inputMicrosPerMillion = Math.max(0, inputCentsPerMillion) * 10_000L;
        this.outputMicrosPerMillion = Math.max(0, outputCentsPerMillion) * 10_000L;
    }

    /** 调用前检查；units 用于把图片/联网等多倍成本算进去。 */
    public void check(String userId, boolean admin, int units) {
        if (admin) return;
        int need = Math.max(1, units);
        UsageStore.Quota quota = store.quota(userId);
        UsageStore.Usage today = store.todayUsage(userId);
        UsageStore.Usage month = store.monthUsage(userId);
        if (quota.dailyRequests() > 0 && today.requests() + need > quota.dailyRequests()) {
            throw new AgentException("今日 AI 调用额度已用完（" + quota.dailyRequests()
                    + " 次），请联系管理员增加额度。");
        }
        if (quota.monthlyRequests() > 0 && month.requests() + need > quota.monthlyRequests()) {
            throw new AgentException("本月 AI 调用额度已用完（" + quota.monthlyRequests()
                    + " 次），请联系管理员增加额度。");
        }
        if (globalMonthly > 0 && store.globalMonthRequests() + need > globalMonthly) {
            throw new AgentException("全站本月 AI 调用额度已用完，请稍后再试。");
        }
    }

    /** 调用后记账。tokens 是按字符估算的，用于额度展示；不是供应商精确 usage。 */
    public void record(String userId, int units, String question, String answer) {
        long input = estimateTokens(question);
        long output = estimateTokens(answer);
        long cost = (input * inputMicrosPerMillion + output * outputMicrosPerMillion) / 1_000_000L;
        store.add(userId, Math.max(1, units), input, output, cost);
    }

    public Map<String, Object> view(String userId) {
        Map<String, Object> body = new LinkedHashMap<>(store.view(userId));
        body.put("defaultDailyRequests", store.defaultsDaily());
        body.put("defaultMonthlyRequests", store.defaultsMonthly());
        body.put("globalMonthlyRequests", globalMonthly);
        body.put("globalMonthRequests", store.globalMonthRequests());
        return body;
    }

    public void setQuota(String userId, int daily, int monthly) {
        store.setQuota(userId, daily, monthly);
    }

    /** 粗略估算：非 ASCII 按 1 字≈1 token，ASCII 按 4 字符≈1 token。 */
    static long estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        long nonAscii = 0, ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) nonAscii++; else ascii++;
        }
        return Math.max(1, nonAscii + (ascii + 3) / 4);
    }
}
