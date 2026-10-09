package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationMeta;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话(对话框)管理接口。
 *
 * <pre>
 *   GET    /api/conversations           列出全部会话 + 默认会话 id
 *   POST   /api/conversations           新建 {"title":"可选"}
 *   GET    /api/conversations/{id}      读某个会话的完整聊天记录
 *   PATCH  /api/conversations/{id}      改名 {"title":"新名字"}
 *   DELETE /api/conversations/{id}      删除(默认会话会 400)
 * </pre>
 *
 * <p>提问接口在 {@link AgentController}:请求体里带上 {@code conversationId} 就知道该记到哪个框里;
 * 不带 = 默认会话(不破坏老前端)。
 */
@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public Map<String, Object> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultId", Conversation.DEFAULT_ID);
        body.put("conversations", service.list());
        return body;
    }

    @GetMapping("/conversations/{id}")
    public Conversation get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/conversations")
    public ConversationMeta create(@RequestBody(required = false) CreateRequest req) {
        return service.create(req == null ? null : req.title());
    }

    @PatchMapping("/conversations/{id}")
    public ConversationMeta rename(@PathVariable String id, @RequestBody(required = false) RenameRequest req) {
        return service.rename(id, req == null ? null : req.title());
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", id);
        body.put("count", service.list().size());
        return body;
    }

    /** 新建请求体:{"title":"物理复习"} —— 不传就用默认的"新对话"。 */
    public record CreateRequest(String title) {
    }

    /** 改名请求体:{"title":"新名字"}。 */
    public record RenameRequest(String title) {
    }
}
