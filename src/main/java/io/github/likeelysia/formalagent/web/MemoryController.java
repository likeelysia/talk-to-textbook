package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.memory.CoreMemoryStore;
import io.github.likeelysia.formalagent.memory.LongTermMemory;
import io.github.likeelysia.formalagent.memory.MemoryConflict;
import io.github.likeelysia.formalagent.memory.MemoryConflictStore;
import io.github.likeelysia.formalagent.memory.MemoryEntry;
import io.github.likeelysia.formalagent.service.UserScopeFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆的治理接口 —— 让"助手记住了什么"变成用户看得见、改得动、删得掉的东西。
 *
 * <pre>
 *   GET    /api/memory?conversationId=xxx           这个会话记住了些什么(新的在前)
 *   POST   /api/memory   {"conversationId","text"}  用户手动加一条(直接钉住,永不淘汰)
 *   PATCH  /api/memory/{id}                         改文字 / 钉住 / 取消钉住
 *   DELETE /api/memory/{id}?conversationId=xxx      删一条
 *   DELETE /api/memory?conversationId=xxx           忘掉这个会话的全部记忆
 *   GET    /api/memory/export?conversationId=xxx    导出 JSON 自己带走
 * </pre>
 *
 * <p><b>为什么这几个接口不挂管理端密钥</b>:它们动的是"用户自己的记忆",不是知识库 ——
 * 和 {@link ConversationController} 一样属于会话级接口(每个会话只看自己那份记忆域)。
 *
 * <p><b>按认证 owner 隔离</b>:MVC 请求一律从 {@link CurrentUser} 取 owner,再用
 * {@link UserScopeFactory} 拿这个 owner 专属的 {@code LongTermMemory} / {@code CoreMemoryStore}
 * / {@code MemoryConflictStore};请求体里的 conversationId 只用来选会话,绝不拿来当身份。
 * 无 Authentication 的重载只给旧单测/CLI 用。
 *
 * <p><b>⚠️ 绝不把向量吐出去</b>:一条记忆的 embedding 是 512 个 float(好几 KB),
 * 前端一个都用不上 → 出口统一走 {@link MemoryView}。
 */
@RestController
@RequestMapping("/api")
public class MemoryController {

    private final LongTermMemory legacyMemory;
    private final CoreMemoryStore legacyCoreMemory;
    private final MemoryConflictStore legacyConflicts;
    private final UserScopeFactory users;
    private final CurrentUser currentUser;

    @Autowired
    public MemoryController(LongTermMemory memory, CoreMemoryStore coreMemory,
                            MemoryConflictStore conflicts, UserScopeFactory users,
                            CurrentUser currentUser) {
        this.legacyMemory = memory;
        this.legacyCoreMemory = coreMemory;
        this.legacyConflicts = conflicts;
        this.users = users;
        this.currentUser = currentUser;
    }

    /** 测试 / 手工装配用:不接用户作用域。 */
    public MemoryController(LongTermMemory memory, CoreMemoryStore coreMemory,
                            MemoryConflictStore conflicts) {
        this(memory, coreMemory, conflicts, null, null);
    }

    /** 测试 / 手工装配用:不接冲突记录。 */
    public MemoryController(LongTermMemory memory, CoreMemoryStore coreMemory) {
        this(memory, coreMemory, null);
    }

    @GetMapping("/memory")
    public Map<String, Object> list(Authentication auth,
                                    @RequestParam(required = false) String conversationId,
                                    @RequestParam(defaultValue = "false") boolean includeInactive) {
        return body(memory(auth).list(conversationId, includeInactive));
    }

    /** 核心档案(每个 owner 一份,每轮注入)。 */
    @GetMapping("/memory/core")
    public Map<String, Object> core(Authentication auth) {
        CoreMemoryStore core = coreMemory(auth);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", core.read());
        body.put("maxChars", core.maxChars());
        return body;
    }

    /** 保存核心档案:{"text":"..."}。 */
    @PutMapping("/memory/core")
    public Map<String, Object> saveCore(Authentication auth,
                                        @RequestBody(required = false) CoreRequest req) {
        CoreMemoryStore core = coreMemory(auth);
        String text = core.save(req == null ? "" : req.text());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("maxChars", core.maxChars());
        return body;
    }

    /** 核心档案冲突的待确认列表(自动提炼发现,不自动改核心档案)。 */
    @GetMapping("/memory/conflicts")
    public Map<String, Object> conflicts(Authentication auth) {
        MemoryConflictStore store = conflictStore(auth);
        List<MemoryConflict> list = store == null ? List.of() : store.list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", list.size());
        body.put("conflicts", list);
        return body;
    }

    /** 忽略一条冲突提示。 */
    @DeleteMapping("/memory/conflicts/{id}")
    public Map<String, Object> dismissConflict(Authentication auth, @PathVariable String id) {
        MemoryConflictStore store = conflictStore(auth);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("dismissed", store != null && store.dismiss(id));
        return body;
    }

    @PostMapping("/memory")
    public MemoryView add(Authentication auth, @RequestBody(required = false) AddRequest req) {
        return MemoryView.of(memory(auth).remember(
                req == null ? null : req.conversationId(),
                req == null ? null : req.text()));
    }

    @PatchMapping("/memory/{id}")
    public MemoryView edit(Authentication auth, @PathVariable String id,
                           @RequestBody(required = false) EditRequest req) {
        return MemoryView.of(memory(auth).edit(
                req == null ? null : req.conversationId(),
                id,
                req == null ? null : req.text(),
                req == null ? null : req.pinned()));
    }

    @DeleteMapping("/memory/{id}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String id,
                                      @RequestParam(required = false) String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("removed", memory(auth).forget(conversationId, id));
        return body;
    }

    @DeleteMapping("/memory")
    public Map<String, Object> forgetAll(Authentication auth,
                                         @RequestParam(required = false) String conversationId) {
        LongTermMemory scoped = memory(auth);
        scoped.forgetAll(conversationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("forgotten", true);
        body.put("count", scoped.list(conversationId).size());
        return body;
    }

    /** 导出:带上 Content-Disposition,浏览器直接当附件下载(用户有权把自己的记忆带走)。 */
    @GetMapping("/memory/export")
    public ResponseEntity<Map<String, Object>> export(
            Authentication auth, @RequestParam(required = false) String conversationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"long-term-memory.json\"")
                .body(body(memory(auth).list(conversationId, true)));
    }

    private LongTermMemory memory(Authentication auth) {
        return users == null ? legacyMemory : users.of(currentUser.id(auth)).memory();
    }

    private CoreMemoryStore coreMemory(Authentication auth) {
        return users == null ? legacyCoreMemory : users.of(currentUser.id(auth)).coreMemory();
    }

    private MemoryConflictStore conflictStore(Authentication auth) {
        return users == null ? legacyConflicts : users.of(currentUser.id(auth)).conflicts();
    }

    private static Map<String, Object> body(List<MemoryEntry> entries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", entries.size());
        body.put("memories", entries.stream().map(MemoryView::of).toList());
        return body;
    }

    // ==================== 兼容旧单测/CLI 的无 Authentication 调用 ====================
    // 这些不是 MVC 映射；生产请求一律走上面的带 Authentication 版本。

    public Map<String, Object> list(String conversationId, boolean includeInactive) {
        return list(null, conversationId, includeInactive);
    }

    public Map<String, Object> core() {
        return core(null);
    }

    public Map<String, Object> saveCore(CoreRequest req) {
        return saveCore(null, req);
    }

    public Map<String, Object> conflicts() {
        return conflicts(null);
    }

    public Map<String, Object> dismissConflict(String id) {
        return dismissConflict(null, id);
    }

    public MemoryView add(AddRequest req) {
        return add(null, req);
    }

    public MemoryView edit(String id, EditRequest req) {
        return edit(null, id, req);
    }

    public Map<String, Object> delete(String id, String conversationId) {
        return delete(null, id, conversationId);
    }

    public Map<String, Object> forgetAll(String conversationId) {
        return forgetAll(null, conversationId);
    }

    public ResponseEntity<Map<String, Object>> export(String conversationId) {
        return export(null, conversationId);
    }

    /** 手动加一条:{"conversationId":"可选","text":"要记住的事"}。 */
    public record AddRequest(String conversationId, String text) {
    }

    /** 改一条:{"conversationId":"可选","text":"可选(不传=不改文字)","pinned":true/false(不传=不改钉住)}。 */
    public record EditRequest(String conversationId, String text, Boolean pinned) {
    }

    /** 保存核心档案:{"text":"..."}。 */
    public record CoreRequest(String text) {
    }

    /** 出口视图(**故意不带 embedding**):一条记忆能给人看的就这些。 */
    public record MemoryView(String id, String text, boolean pinned, String origin, long createdAt,
                             long lastAccessAt, String conversationId, String status,
                             long updatedAt, List<String> supersedes) {

        static MemoryView of(MemoryEntry entry) {
            return new MemoryView(entry.id(), entry.text(), entry.pinned(), entry.origin(),
                    entry.createdAt(), entry.lastAccessAt(), entry.conversationId(),
                    entry.status(), entry.updatedAt(), entry.supersedes());
        }
    }
}
