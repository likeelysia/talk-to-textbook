package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationMeta;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.service.UserScopeFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 会话接口；生产请求按认证 owner 取独立 UserData，旧测试/CLI 保留无 Authentication 重载。 */
@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationService legacy;
    private final UserScopeFactory users;
    private final CurrentUser currentUser;

    public ConversationController(ConversationService legacy) {
        this(legacy, null, null);
    }

    @Autowired
    public ConversationController(ConversationService legacy, UserScopeFactory users,
                                  CurrentUser currentUser) {
        this.legacy = legacy;
        this.users = users;
        this.currentUser = currentUser;
    }

    @GetMapping("/conversations")
    public Map<String, Object> list(Authentication auth) {
        ConversationService service = service(auth);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("defaultId", Conversation.DEFAULT_ID);
        body.put("conversations", service.list());
        return body;
    }

    @GetMapping("/conversations/{id}")
    public Conversation get(Authentication auth, @PathVariable String id) {
        return service(auth).get(id);
    }

    @PostMapping("/conversations")
    public ConversationMeta create(Authentication auth,
                                   @RequestBody(required = false) CreateRequest req) {
        return service(auth).create(req == null ? null : req.title());
    }

    @PatchMapping("/conversations/{id}")
    public ConversationMeta rename(Authentication auth, @PathVariable String id,
                                   @RequestBody(required = false) RenameRequest req) {
        return service(auth).rename(id, req == null ? null : req.title());
    }

    @PutMapping("/conversations/{id}/memory-scope")
    public ConversationMeta shareMemory(Authentication auth, @PathVariable String id,
                                        @RequestBody(required = false) ScopeRequest req) {
        return service(auth).shareMemory(id, req == null ? null : req.scopeId());
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String id) {
        ConversationService service = service(auth);
        service.delete(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", id);
        body.put("count", service.list().size());
        return body;
    }

    private ConversationService service(Authentication auth) {
        return users == null ? legacy : users.of(currentUser.id(auth)).conversations();
    }

    public Map<String, Object> list() {
        return list(null);
    }

    public Conversation get(String id) {
        return legacy.get(id);
    }

    public ConversationMeta create(CreateRequest req) {
        return legacy.create(req == null ? null : req.title());
    }

    public ConversationMeta rename(String id, RenameRequest req) {
        return legacy.rename(id, req == null ? null : req.title());
    }

    public ConversationMeta shareMemory(String id, ScopeRequest req) {
        return legacy.shareMemory(id, req == null ? null : req.scopeId());
    }

    public Map<String, Object> delete(String id) {
        return delete(null, id);
    }

    public record CreateRequest(String title) {
    }

    public record RenameRequest(String title) {
    }

    public record ScopeRequest(String scopeId) {
    }
}
