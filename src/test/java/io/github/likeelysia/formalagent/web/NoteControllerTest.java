package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.likeelysia.formalagent.conversation.Note;
import io.github.likeelysia.formalagent.conversation.NoteService;
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

/** 笔记本接口的"翻译"是否正确:请求怎么读、响应怎么拼。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NoteController")
class NoteControllerTest {

    @Mock
    private NoteService notes;

    private NoteController controller;

    @BeforeEach
    void setUp() {
        controller = new NoteController(notes);
    }

    private static Note note(String id, String conv, String question) {
        return new Note(id, conv, question, 1000L,
                List.of(new Note.Answer("回答正文", 1001L, List.of("search_knowledge({})"))), 2000L);
    }

    @Test
    @DisplayName("POST /api/notes:回 savedCount / skippedCount / saved[]")
    void save() {
        when(notes.save(eq("c1"), eq(List.of(0, 2))))
                .thenReturn(new NoteService.SaveResult(List.of(note("n1", "c1", "位移是什么?")), 1));

        Map<String, Object> body = controller.save(new NoteController.SaveRequest("c1", List.of(0, 2)));

        assertThat(body).containsEntry("savedCount", 1).containsEntry("skippedCount", 1);
        assertThat((List<?>) body.get("saved")).hasSize(1);
        verify(notes).save("c1", List.of(0, 2));
    }

    @Test
    @DisplayName("GET /api/notes:按 conversationId 过滤;不传就是全部")
    void list() {
        when(notes.list()).thenReturn(List.of(note("n1", "c1", "问题一"), note("n2", "c2", "问题二")));

        Map<String, Object> all = controller.list(null);
        Map<String, Object> onlyC1 = controller.list("c1");

        assertThat(all).containsEntry("count", 2);
        assertThat(onlyC1).containsEntry("count", 1);
        assertThat((NoteController.NoteView) ((List<?>) onlyC1.get("notes")).get(0))
                .extracting(NoteController.NoteView::id).isEqualTo("n1");
    }

    @Test
    @DisplayName("GET /api/notes/{id}:详情带 answerCount 和回答")
    void detail() {
        when(notes.get("n1")).thenReturn(note("n1", "c1", "位移是什么?"));

        NoteController.NoteView view = controller.get("n1");

        assertThat(view.id()).isEqualTo("n1");
        assertThat(view.question()).isEqualTo("位移是什么?");
        assertThat(view.answerCount()).isEqualTo(1);
        assertThat(view.answers().get(0).text()).isEqualTo("回答正文");
        assertThat(view.answers().get(0).trace()).containsExactly("search_knowledge({})");
    }

    @Test
    @DisplayName("DELETE /api/notes/{id}:回 removed")
    void delete() {
        when(notes.delete("n1")).thenReturn(true);
        assertThat(controller.delete("n1")).containsEntry("removed", true).containsEntry("id", "n1");
    }

    @Test
    @DisplayName("导出:带 Content-Disposition,浏览器当附件下载")
    void export() {
        when(notes.list()).thenReturn(List.of(note("n1", "c1", "问题")));

        ResponseEntity<Map<String, Object>> response = controller.export();

        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("attachment").contains("notebook.json");
        assertThat(response.getBody()).containsEntry("count", 1);
    }
}
