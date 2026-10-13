package io.github.likeelysia.formalagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话业务规则:默认会话兜底、首条提问当标题、不存在的会话一律报错。 */
@DisplayName("ConversationService 会话业务")
class ConversationServiceTest {

    @TempDir
    Path tmp;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(new ConversationStore(new ObjectMapper(), tmp));
        service.init();                                   // 模拟启动时的 @PostConstruct
    }

    @Test
    @DisplayName("一启动就有「默认对话」")
    void defaultConversationExistsFromTheStart() {
        List<ConversationMeta> all = service.list();

        assertThat(all).extracting(ConversationMeta::id).containsExactly(Conversation.DEFAULT_ID);
        assertThat(all.get(0).title()).isEqualTo(Conversation.DEFAULT_TITLE);
    }

    @Test
    @DisplayName("init 跑两遍也不会多出会话")
    void initIsIdempotent() {
        service.init();
        assertThat(service.list()).hasSize(1);
    }

    @Test
    @DisplayName("新建 -> 能读回来;标题不传就是「新对话」")
    void createAndGet() {
        ConversationMeta meta = service.create(null);

        assertThat(meta.title()).isEqualTo(Conversation.NEW_TITLE);
        assertThat(service.get(meta.id()).id()).isEqualTo(meta.id());
    }

    @Test
    @DisplayName("改名")
    void rename() {
        ConversationMeta meta = service.create("旧名字");
        assertThat(service.rename(meta.id(), "  新名字  ").title()).isEqualTo("新名字");
    }

    @Test
    @DisplayName("改名:空标题被拒")
    void renameRejectsBlankTitle() {
        assertThatThrownBy(() -> service.rename(Conversation.DEFAULT_ID, "   "))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("标题");
    }

    @Test
    @DisplayName("删掉一个自建会话;默认会话不许删")
    void deleteGuards() {
        ConversationMeta meta = service.create("待删");
        service.delete(meta.id());
        assertThat(service.list()).extracting(ConversationMeta::id).doesNotContain(meta.id());

        assertThatThrownBy(() -> service.delete(Conversation.DEFAULT_ID))
                .isInstanceOf(AgentException.class);
    }

    @Test
    @DisplayName("记一轮问答:两条消息落库,首条提问自动变成标题")
    void recordAppendsAndAutoTitles() {
        ConversationMeta meta = service.create(null);
        service.record(meta.id(), "动量守恒的条件是什么?", "合外力为零。", List.of("search_knowledge({})"));

        Conversation loaded = service.get(meta.id());
        assertThat(loaded.messages()).hasSize(2);
        assertThat(loaded.messages().get(0).content()).isEqualTo("动量守恒的条件是什么?");
        assertThat(loaded.messages().get(1).trace()).containsExactly("search_knowledge({})");
        assertThat(loaded.title()).isEqualTo("动量守恒的条件是什么?");
    }

    @Test
    @DisplayName("标题太长会被截断")
    void autoTitleIsTruncated() {
        ConversationMeta meta = service.create(null);
        service.record(meta.id(), "请把牛顿第二定律和动量定理在使用条件上的区别详细讲一遍", "…", null);

        assertThat(service.get(meta.id()).title()).hasSizeLessThan(30).endsWith("…");
    }

    @Test
    @DisplayName("applySummary:摘要 + 水位一起写;水位只能前进不能后退")
    void applySummaryAdvancesWatermark() {
        ConversationMeta meta = service.create("摘要测试");
        service.record(meta.id(), "问题一", "回答一", null);

        service.applySummary(meta.id(), "讲了位移的定义", 2);
        Conversation conv = service.get(meta.id());
        assertThat(conv.summary()).isEqualTo("讲了位移的定义");
        assertThat(conv.summarizedCount()).isEqualTo(2);

        service.applySummary(meta.id(), "退回去", 1);
        assertThat(service.get(meta.id()).summarizedCount()).isEqualTo(2);
        assertThat(service.get(meta.id()).messages()).hasSize(2);       // 原文照样在
    }

    @Test
    @DisplayName("markMemorized:长期记忆水位只能前进不能后退")
    void markMemorizedOnlyMovesForward() {
        ConversationMeta meta = service.create("记忆水位");
        service.record(meta.id(), "问题一", "回答一", null);

        service.markMemorized(meta.id(), 2);
        assertThat(service.get(meta.id()).memoryCount()).isEqualTo(2);

        service.markMemorized(meta.id(), 1);
        assertThat(service.get(meta.id()).memoryCount()).isEqualTo(2);
        assertThat(service.get(meta.id()).messages()).hasSize(2);        // 原文照样在
    }

    @Test
    @DisplayName("conversationId 为空 -> 记进默认会话(老前端不带这个字段也能用)")
    void blankIdFallsBackToDefault() {
        service.record(null, "你好", "你好呀", null);

        assertThat(service.get(Conversation.DEFAULT_ID).messages()).hasSize(2);
    }

    @Test
    @DisplayName("记进一个不存在的会话 -> 报错,不偷偷新建")
    void recordIntoUnknownConversationFails() {
        assertThatThrownBy(() -> service.record("nope", "q", "a", null))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("requireExisting:空 -> 默认会话;不存在 -> 报错(用于「先验 id 再花钱调模型」)")
    void requireExisting() {
        assertThat(service.requireExisting(null)).isEqualTo(Conversation.DEFAULT_ID);
        assertThat(service.requireExisting("  ")).isEqualTo(Conversation.DEFAULT_ID);
        assertThatThrownBy(() -> service.requireExisting("ghost"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("会话不存在");
    }

    // ==================== 记忆域(共享 / 拆开) ====================

    @Test
    @DisplayName("默认:每个会话的记忆域就是它自己")
    void scopeDefaultsToItself() {
        ConversationMeta a = service.create("甲");
        ConversationMeta b = service.create("乙");

        assertThat(service.memoryScopeOf(a.id())).isEqualTo(a.id());
        assertThat(service.memoryScopeOf(b.id())).isEqualTo(b.id());
    }

    @Test
    @DisplayName("共享:乙加入甲 -> 两边解析到同一个记忆域(甲)")
    void sharePointsToTheSameScope() {
        ConversationMeta a = service.create("甲");
        ConversationMeta b = service.create("乙");

        ConversationMeta updated = service.shareMemory(b.id(), a.id());

        assertThat(updated.id()).isEqualTo(b.id());
        assertThat(updated.scopeId()).isEqualTo(a.id());
        assertThat(service.memoryScopeOf(b.id())).isEqualTo(service.memoryScopeOf(a.id()));
    }

    @Test
    @DisplayName("共享存的是“根”:丙加入已共享的乙 -> 最终指向甲")
    void shareStoresTheRoot() {
        ConversationMeta a = service.create("甲");
        ConversationMeta b = service.create("乙");
        ConversationMeta c = service.create("丙");
        service.shareMemory(b.id(), a.id());              // 乙 -> 甲

        assertThat(service.shareMemory(c.id(), b.id()).scopeId()).isEqualTo(a.id());   // 丙 -> 甲(不是乙)
        assertThat(service.memoryScopeOf(c.id())).isEqualTo(service.memoryScopeOf(a.id()));
    }

    @Test
    @DisplayName("链式指向也能走到根:老数据甲->乙、乙->丙,甲应解析到丙")
    void resolvedByWalkingTheChain() {
        ConversationMeta a = service.create("甲");
        ConversationMeta b = service.create("乙");
        ConversationMeta c = service.create("丙");
        service.shareMemory(a.id(), b.id());              // 甲 -> 乙
        service.shareMemory(b.id(), c.id());              // 乙 -> 丙(存的是根:丙,所以乙直接指丙)

        // 就算库里真出现“甲->乙->丙”这种长链,解析也必须走到根
        assertThat(service.memoryScopeOf(a.id())).isEqualTo(c.id());
    }

    @Test
    @DisplayName("加入同一个组不会把自己绕进去(幂等)")
    void shareIntoOwnGroupIsNoop() {
        ConversationMeta a = service.create("甲");
        ConversationMeta b = service.create("乙");
        service.shareMemory(b.id(), a.id());              // 乙 -> 甲

        ConversationMeta again = service.shareMemory(a.id(), b.id());   // 甲“加入”乙所在的组 -> 还是甲自己
        assertThat(again.scopeId()).isEqualTo(a.id());
        assertThat(service.memoryScopeOf(b.id())).isEqualTo(a.id());     // 组没散
    }

    @Test
    @DisplayName("拆开:传 null / 穿空 / 传自己 -> 都回到独立(各用各的)")
    void unshareGoesBackToItself() {
        ConversationMeta a = service.create("甲");
        ConversationMeta b = service.create("乙");
        service.shareMemory(b.id(), a.id());

        assertThat(service.shareMemory(b.id(), null).scopeId()).isEqualTo(b.id());
        service.shareMemory(b.id(), a.id());
        assertThat(service.shareMemory(b.id(), "  ").scopeId()).isEqualTo(b.id());
        service.shareMemory(b.id(), a.id());
        assertThat(service.shareMemory(b.id(), b.id()).scopeId()).isEqualTo(b.id());
    }

    @Test
    @DisplayName("共享对象被删了:仍能解析(不报错、不串到别人家)")
    void scopeSurvivesTargetDeletion() {
        ConversationMeta a = service.create("甲");
        ConversationMeta b = service.create("乙");
        service.shareMemory(b.id(), a.id());

        service.delete(a.id());

        assertThat(service.memoryScopeOf(b.id())).isEqualTo(a.id());     // 停在那个名字上,记忆文件还在
    }

    @Test
    @DisplayName("共享给一个不存在的会话 / 不合理的 id -> 报错")
    void shareRejectsBadTarget() {
        ConversationMeta a = service.create("甲");

        assertThatThrownBy(() -> service.shareMemory(a.id(), "ghost"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("会话不存在");
        assertThatThrownBy(() -> service.shareMemory(a.id(), "../etc/passwd"))
                .isInstanceOf(AgentException.class);
    }
}
