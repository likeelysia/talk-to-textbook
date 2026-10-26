package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.conversation.Note;
import io.github.likeelysia.formalagent.conversation.NoteService;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final CurrentUser currentUser;

    public NoteController(NoteService notes) {
        this(notes, null);
    }

    @Autowired
    public NoteController(NoteService notes, CurrentUser currentUser) {
        this.notes = notes;
        this.currentUser = currentUser;
    }

    @PostMapping("/notes")
    public Map<String, Object> save(Authentication auth,
                                    @RequestBody(required = false) SaveRequest req) {
        if (currentUser != null && !currentUser.admin(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "会话尚未完成多账号隔离；测试账号请使用手工笔记接口");
        }
        NoteService.SaveResult result = notes.save(
                req == null ? null : req.conversationId(),
                req == null ? null : req.indexes());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("savedCount", result.saved().size());
        body.put("skippedCount", result.skipped());
        body.put("saved", result.saved().stream().map(NoteView::of).toList());
        return body;
    }

    /** A/B 首轮受控入口：问题+回答直接生成笔记，不引用尚未隔离的会话。 */
    @PostMapping("/notes/manual")
    public NoteView manual(Authentication auth, @RequestBody(required = false) ManualRequest req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求不能为空");
        return NoteView.of(notes.saveManual(owner(auth), req.question(), req.answers()));
    }

    @GetMapping("/notes")
    public Map<String, Object> list(Authentication auth,
                                    @RequestParam(required = false) String conversationId) {
        List<Note> all = (currentUser == null ? notes.list() : notes.list(owner(auth))).stream()
                .filter(n -> conversationId == null || conversationId.isBlank()
                        || conversationId.equals(n.conversationId()))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", all.size());
        body.put("notes", all.stream().map(NoteView::of).toList());
        return body;
    }

    @GetMapping("/notes/{id}")
    public NoteView get(Authentication auth, @PathVariable String id) {
        try {
            return NoteView.of(currentUser == null ? notes.get(id) : notes.get(owner(auth), id));
        } catch (AgentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在");
        }
    }

    @PatchMapping("/notes/{id}")
    public NoteView edit(Authentication auth, @PathVariable String id,
                         @RequestBody(required = false) EditRequest req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求不能为空");
        try {
            Note note = currentUser == null
                    ? notes.edit("admin", id, req.question(), req.answers())
                    : notes.edit(owner(auth), id, req.question(), req.answers());
            return NoteView.of(note);
        } catch (AgentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在");
        }
    }

    @DeleteMapping("/notes/{id}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String id) {
        boolean removed = currentUser == null ? notes.delete(id) : notes.delete(owner(auth), id);
        if (currentUser != null && !removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "笔记不存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("removed", removed);
        return body;
    }

    @GetMapping("/notes/export")
    public ResponseEntity<Map<String, Object>> export(Authentication auth) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"notebook.json\"")
                .body(list(auth, null));
    }

    private String owner(Authentication auth) {
        try {
            return currentUser.id(auth);
        } catch (AgentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
    }

    /** 存笔记请求:{"conversationId":"...","indexes":[3,5]}。 */
    public record SaveRequest(String conversationId, List<Integer> indexes) {
    }

    /** 手工笔记请求，仅用于已隔离的笔记切片。 */
    public record ManualRequest(String question, List<String> answers) {
    }

    public record EditRequest(String question, List<String> answers) {
    }

    // ==================== 兼容旧单测/CLI 的无 Authentication 调用 ====================
    // 这些不是 MVC 映射；生产请求一律走上面的带 Authentication 版本。

    public Map<String, Object> save(SaveRequest req) {
        return save(null, req);
    }

    public Map<String, Object> list(String conversationId) {
        return list(null, conversationId);
    }

    public NoteView get(String id) {
        return get(null, id);
    }

    public Map<String, Object> delete(String id) {
        return delete(null, id);
    }

    public ResponseEntity<Map<String, Object>> export() {
        return export(null);
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
