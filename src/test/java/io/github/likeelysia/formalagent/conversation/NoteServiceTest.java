package io.github.likeelysia.formalagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 「存笔记」的业务逻辑:挑相关回答(乙)+ 去重 + 降级(甲)。 */
@DisplayName("NoteService 存笔记")
class NoteServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 假 embedder:只按关键词分两个正交方向 —— 不同类必须"不相似"(踩过的教训)。 */
    private static final EmbeddingClient FAKE = text -> {
        if (text.contains("位移")) {
            return new float[] {1f, 0f};
        }
        if (text.contains("线程")) {
            return new float[] {0f, 1f};
        }
        return new float[] {0.7f, 0.7f};
    };

    @TempDir
    Path tmp;

    private ConversationService conversations;
    private NoteStore notes;

    @BeforeEach
    void setUp() {
        ConversationStore store = new ConversationStore(MAPPER, tmp.resolve("conversations"));
        conversations = new ConversationService(store);
        notes = new NoteStore(MAPPER, tmp.resolve("notes.json"));
    }

    /** 造一个会话:位移 → 线程 → 再问位移(共 3 轮,6 条消息)。 */
    private String seedConversation() {
        ConversationMeta c = conversations.create("测试");
        conversations.record(c.id(), "位移是什么?", "位移是位置矢量的改变量,位移这件事要这么理解。", null);
        conversations.record(c.id(), "线程是什么?", "线程是进程里的执行单元。", null);
        conversations.record(c.id(), "再讲讲位移", "再补充一点关于位移的细节。", null);
        return c.id();
    }

    @Test
    @DisplayName("乙:同一轮必收,另有语义相关的其它回答也一并收")
    void collectsRelatedAnswers() {
        String cid = seedConversation();
        NoteService service = new NoteService(conversations, notes, FAKE);

        NoteService.SaveResult result = service.save(cid, List.of(0));   // 第 0 条 = "位移是什么?"

        assertThat(result.saved()).hasSize(1);
        Note note = result.saved().get(0);
        assertThat(note.question()).isEqualTo("位移是什么?");
        // 第 1 条(同一轮)+ 第 5 条(第三轮那条"再补充一点关于位移的细节")都含"位移"
        assertThat(note.answers()).hasSize(2);
        assertThat(note.answers().get(0).text()).contains("位移是位置矢量的改变量");
        assertThat(note.answers().get(1).text()).contains("再补充一点关于位移");
    }

    @Test
    @DisplayName("甲:embedding 不可用时,退化成只收同一轮那条")
    void fallsBackToSameTurn() {
        String cid = seedConversation();
        NoteService service = new NoteService(conversations, notes, null);   // 没有 embedder

        NoteService.SaveResult result = service.save(cid, List.of(0));

        assertThat(result.saved()).hasSize(1);
        assertThat(result.saved().get(0).answers()).hasSize(1);
        assertThat(result.saved().get(0).answers().get(0).text()).contains("位移是位置矢量的改变量");
    }

    @Test
    @DisplayName("embedding 抛异常也别失败 —— 一样退回甲")
    void embeddingFailureFallsBack() {
        String cid = seedConversation();
        EmbeddingClient broken = text -> {
            throw new AgentException("8090 没开");
        };
        NoteService service = new NoteService(conversations, notes, broken);

        NoteService.SaveResult result = service.save(cid, List.of(0));
        assertThat(result.saved()).hasSize(1);
        assertThat(result.saved().get(0).answers()).hasSize(1);
    }

    @Test
    @DisplayName("去重:同一问题再存 = 跳过,不产生第二条")
    void dedupes() {
        String cid = seedConversation();
        NoteService service = new NoteService(conversations, notes, FAKE);

        assertThat(service.save(cid, List.of(0)).saved()).hasSize(1);
        NoteService.SaveResult again = service.save(cid, List.of(0));
        assertThat(again.saved()).isEmpty();
        assertThat(again.skipped()).isEqualTo(1);
        assertThat(service.list()).hasSize(1);
    }

    @Test
    @DisplayName("只认用户的消息:勾到 AI 回答的下标就当没勾")
    void ignoresAssistantMessages() {
        String cid = seedConversation();
        NoteService service = new NoteService(conversations, notes, FAKE);

        NoteService.SaveResult result = service.save(cid, List.of(1));   // 第 1 条是 AI 回答
        assertThat(result.saved()).isEmpty();
        assertThat(service.list()).isEmpty();
    }

    @Test
    @DisplayName("多选:一次勾两问,各存各的")
    void savesSeveralAtOnce() {
        String cid = seedConversation();
        NoteService service = new NoteService(conversations, notes, FAKE);

        NoteService.SaveResult result = service.save(cid, List.of(0, 2));
        assertThat(result.saved()).hasSize(2);
        assertThat(service.list()).extracting(Note::question)
                .containsExactlyInAnyOrder("位移是什么?", "线程是什么?");
    }

    @Test
    @DisplayName("参数不合法就报错:空会话 / 空勾选 / 不存在的会话")
    void validatesInput() {
        NoteService service = new NoteService(conversations, notes, FAKE);
        assertThatThrownBy(() -> service.save(null, List.of(0))).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> service.save("c1", List.of())).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> service.save("不存在", List.of(0))).isInstanceOf(AgentException.class);
    }

    @Test
    @DisplayName("取详情 / 删除")
    void getAndDelete() {
        String cid = seedConversation();
        NoteService service = new NoteService(conversations, notes, FAKE);
        String id = service.save(cid, List.of(0)).saved().get(0).id();

        assertThat(service.get(id).question()).isEqualTo("位移是什么?");
        assertThat(service.delete(id)).isTrue();
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(AgentException.class);
    }
}
