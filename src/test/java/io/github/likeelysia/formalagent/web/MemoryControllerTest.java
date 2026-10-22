package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.memory.CoreMemoryStore;
import io.github.likeelysia.formalagent.memory.LongTermMemory;
import io.github.likeelysia.formalagent.memory.MemoryConflict;
import io.github.likeelysia.formalagent.memory.MemoryConflictStore;
import io.github.likeelysia.formalagent.memory.MemoryEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

/** 记忆治理接口的"翻译"是否正确:请求怎么读、响应怎么拼、什么不该出去。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemoryController")
class MemoryControllerTest {

    @Mock
    private LongTermMemory memory;

    @Mock
    private CoreMemoryStore coreMemory;

    @Mock
    private MemoryConflictStore conflicts;

    private MemoryController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryController(memory, coreMemory, conflicts);
    }

    private static MemoryEntry entry() {
        return new MemoryEntry("m1", "default", "用户叫鹏哥", "default", 1000L, 2000L, true,
                MemoryEntry.ORIGIN_USER, new float[] {1, 2, 3}, 0L, MemoryEntry.STATUS_ACTIVE, List.of());
    }

    @Test
    @DisplayName("列表:回 count + 视图(id / 文字 / 钉住 / 来源都在)")
    void listWrapsViews() {
        when(memory.list("c1", false)).thenReturn(List.of(entry()));

        Map<String, Object> body = controller.list("c1", false);

        assertThat(body.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<MemoryController.MemoryView> views = (List<MemoryController.MemoryView>) body.get("memories");
        assertThat(views).hasSize(1);
        assertThat(views.get(0).text()).isEqualTo("用户叫鹏哥");
        assertThat(views.get(0).pinned()).isTrue();
        assertThat(views.get(0).origin()).isEqualTo(MemoryEntry.ORIGIN_USER);
    }

    @Test
    @DisplayName("⚠️ 向量绝不出去:序列化出来的视图里没有 embedding 字段")
    void viewNeverLeaksTheVector() throws Exception {
        String json = new ObjectMapper().writeValueAsString(MemoryController.MemoryView.of(entry()));

        assertThat(json).doesNotContain("embedding").contains("\"pinned\":true");
    }

    @Test
    @DisplayName("手动加一条:body 缺省也不会 NPE")
    void addToleratesMissingBody() {
        when(memory.remember(any(), any())).thenReturn(entry());

        controller.add(null);

        verify(memory).remember(null, null);
    }

    @Test
    @DisplayName("改一条:null 一律透传(交给 service 决定“不改”)")
    void editPassesNullsThrough() {
        when(memory.edit(any(), eq("m1"), any(), any())).thenReturn(entry());

        controller.edit("m1", new MemoryController.EditRequest(null, null, null));

        verify(memory).edit(null, "m1", null, null);
    }

    @Test
    @DisplayName("删一条:回 removed 标记")
    void deleteReportsRemoved() {
        when(memory.forget("c1", "m1")).thenReturn(true);

        Map<String, Object> body = controller.delete("m1", "c1");

        assertThat(body.get("id")).isEqualTo("m1");
        assertThat(body.get("removed")).isEqualTo(true);
    }

    @Test
    @DisplayName("忘掉全部:回 forgotten + 剩下的条数")
    void forgetAllReportsCount() {
        when(memory.list("c1")).thenReturn(List.of());

        Map<String, Object> body = controller.forgetAll("c1");

        verify(memory).forgetAll("c1");
        assertThat(body.get("forgotten")).isEqualTo(true);
        assertThat(body.get("count")).isEqualTo(0);
    }

    @Test
    @DisplayName("核心档案:读文本 + 字数上限")
    void coreMemoryReads() {
        when(coreMemory.read()).thenReturn("我叫鹏哥");
        when(coreMemory.maxChars()).thenReturn(800);

        Map<String, Object> body = controller.core();

        assertThat(body.get("text")).isEqualTo("我叫鹏哥");
        assertThat(body.get("maxChars")).isEqualTo(800);
    }

    @Test
    @DisplayName("核心档案:保存后回显")
    void coreMemorySaves() {
        when(coreMemory.save("新档案")).thenReturn("新档案");
        when(coreMemory.maxChars()).thenReturn(800);

        Map<String, Object> body = controller.saveCore(new MemoryController.CoreRequest("新档案"));

        assertThat(body.get("text")).isEqualTo("新档案");
        verify(coreMemory).save("新档案");
    }

    @Test
    @DisplayName("导出:带 Content-Disposition,浏览器当附件下载")
    void exportIsAnAttachment() {
        when(memory.list("c1", true)).thenReturn(List.of(entry()));

        ResponseEntity<Map<String, Object>> response = controller.export("c1");

        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        assertThat(response.getBody()).containsKey("memories");
    }

    @Test
    @DisplayName("核心档案冲突:列表回 count + 候选文本")
    void conflictsAreListed() {
        MemoryConflict conflict = MemoryConflict.of("s", "用户要就业", "c1");
        when(conflicts.list()).thenReturn(List.of(conflict));

        Map<String, Object> body = controller.conflicts();

        assertThat(body.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<MemoryConflict> list = (List<MemoryConflict>) body.get("conflicts");
        assertThat(list.get(0).candidateText()).isEqualTo("用户要就业");
    }

    @Test
    @DisplayName("冲突可以忽略")
    void conflictsCanBeDismissed() {
        when(conflicts.dismiss("c1")).thenReturn(true);

        Map<String, Object> body = controller.dismissConflict("c1");

        assertThat(body.get("dismissed")).isEqualTo(true);
        verify(conflicts).dismiss("c1");
    }
}
