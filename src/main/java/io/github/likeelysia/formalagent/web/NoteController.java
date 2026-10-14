package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.conversation.Note;
import io.github.likeelysia.formalagent.conversation.NoteService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「笔记本」接口。
 *
 * <pre>
 *   POST   /api/notes   {"conversationId","indexes":[1,4,7]}  把勾选的提问存进笔记本
 *   GET    /api/notes?conversationId=xxx                     整本笔记(新的在前;可按会话过滤)
 *   GET    /api/notes/{id}                                   一条笔记的详情(问题 + 相关回答)
 *   DELETE /api/notes/{id}                                   删一条
 *   GET    /api/notes/export                                 导出 JSON 带走
 * </pre>
 *
 * <p>和 {@link MemoryController} 一样属于"会话级"接口,不挂管理端密钥 —— 它动的是用户自己的笔记。
 */
@RestController
@RequestMapping("/api")
public class NoteController {

    private final NoteService notes;

    public NoteController(NoteService notes) {
        this.notes = notes;
    }

    @PostMapping("/notes")
    public Map<String, Object> save(@RequestBody(required = false) SaveRequest req) {
        NoteService.SaveResult result = notes.save(
                req == null ? null : req.conversationId(),
                req == null ? null : req.indexes());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("savedCount", result.saved().size());
        body.put("skippedCount", result.skipped());
        body.put("saved", result.saved().stream().map(NoteView::of).toList());
        return body;
    }

    @GetMapping("/notes")
    public Map<String, Object> list(@RequestParam(required = false) String conversationId) {
        List<Note> all = notes.list().stream()
                .filter(n -> conversationId == null || conversationId.isBlank()
                        || conversationId.equals(n.conversationId()))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", all.size());
        body.put("notes", all.stream().map(NoteView::of).toList());
        return body;
    }

    @GetMapping("/notes/{id}")
    public NoteView get(@PathVariable String id) {
        return NoteView.of(notes.get(id));
    }

    @DeleteMapping("/notes/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("removed", notes.delete(id));
        return body;
    }

    @GetMapping("/notes/export")
    public ResponseEntity<Map<String, Object>> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"notebook.json\"")
                .body(list(null));
    }

    /** 存笔记请求:{"conversationId":"...","indexes":[3,5]}。 */
    public record SaveRequest(String conversationId, List<Integer> indexes) {
    }

    /** 出口视图:一条笔记(问题 + 相关回答 + 出处线索)。 */
    public record NoteView(String id, String conversationId, String question, long questionAt,
                           long savedAt, int answerCount, List<AnswerView> answers) {

        static NoteView of(Note note) {
            return new NoteView(note.id(), note.conversationId(), note.question(), note.questionAt(),
                    note.savedAt(), note.answers().size(),
                    note.answers().stream().map(AnswerView::of).toList());
        }
    }

    /** 一条回答(正文 + 时间 + 工具轨迹)。 */
    public record AnswerView(String text, long at, List<String> trace) {

        static AnswerView of(Note.Answer answer) {
            return new AnswerView(answer.text(), answer.at(), answer.trace());
        }
    }
}
