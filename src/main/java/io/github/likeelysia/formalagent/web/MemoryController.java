package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.memory.CoreMemoryStore;
import io.github.likeelysia.formalagent.memory.LongTermMemory;
import io.github.likeelysia.formalagent.memory.MemoryConflict;
import io.github.likeelysia.formalagent.memory.MemoryConflictStore;
import io.github.likeelysia.formalagent.memory.MemoryEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
 * <p><b>⚠️ 绝不把向量吐出去</b>:一条记忆的 embedding 是 512 个 float(好几 KB),
 * 前端一个都用不上 → 出口统一走 {@link MemoryView}。
 */
@RestController
@RequestMapping("/api")
public class MemoryController {

    private final LongTermMemory memory;
    private final CoreMemoryStore coreMemory;
    private final MemoryConflictStore conflicts;

    @Autowired
    public MemoryController(LongTermMemory memory, CoreMemoryStore coreMemory,
                            MemoryConflictStore conflicts) {
        this.memory = memory;
        this.coreMemory = coreMemory;
        this.conflicts = conflicts;
    }

    /** 测试 / 手工装配用:不接冲突记录。 */
    public MemoryController(LongTermMemory memory, CoreMemoryStore coreMemory) {
        this(memory, coreMemory, null);
    }

    @GetMapping("/memory")
    public Map<String, Object> list(@RequestParam(required = false) String conversationId,
                                    @RequestParam(defaultValue = "false") boolean includeInactive) {
        return body(memory.list(conversationId, includeInactive));
    }

    /** 核心档案(全局一份,每轮注入)。 */
    @GetMapping("/memory/core")
    public Map<String, Object> core() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", coreMemory.read());
        body.put("maxChars", coreMemory.maxChars());
        return body;
    }

    /** 保存核心档案:{"text":"..."}。 */
    @PutMapping("/memory/core")
    public Map<String, Object> saveCore(@RequestBody(required = false) CoreRequest req) {
        String text = coreMemory.save(req == null ? "" : req.text());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("maxChars", coreMemory.maxChars());
        return body;
    }

    /** 核心档案冲突的待确认列表(自动提炼发现,不自动改核心档案)。 */
    @GetMapping("/memory/conflicts")
    public Map<String, Object> conflicts() {
        List<MemoryConflict> list = conflicts == null ? List.of() : conflicts.list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", list.size());
        body.put("conflicts", list);
        return body;
    }

    /** 忽略一条冲突提示。 */
    @DeleteMapping("/memory/conflicts/{id}")
    public Map<String, Object> dismissConflict(@PathVariable String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("dismissed", conflicts != null && conflicts.dismiss(id));
        return body;
    }

    @PostMapping("/memory")
    public MemoryView add(@RequestBody(required = false) AddRequest req) {
        return MemoryView.of(memory.remember(
                req == null ? null : req.conversationId(),
                req == null ? null : req.text()));
    }

    @PatchMapping("/memory/{id}")
    public MemoryView edit(@PathVariable String id, @RequestBody(required = false) EditRequest req) {
        return MemoryView.of(memory.edit(
                req == null ? null : req.conversationId(),
                id,
                req == null ? null : req.text(),
                req == null ? null : req.pinned()));
    }

    @DeleteMapping("/memory/{id}")
    public Map<String, Object> delete(@PathVariable String id,
                                      @RequestParam(required = false) String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("removed", memory.forget(conversationId, id));
        return body;
    }

    @DeleteMapping("/memory")
    public Map<String, Object> forgetAll(@RequestParam(required = false) String conversationId) {
        memory.forgetAll(conversationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("forgotten", true);
        body.put("count", memory.list(conversationId).size());
        return body;
    }

    /** 导出:带上 Content-Disposition,浏览器直接当附件下载(用户有权把自己的记忆带走)。 */
    @GetMapping("/memory/export")
    public ResponseEntity<Map<String, Object>> export(@RequestParam(required = false) String conversationId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"long-term-memory.json\"")
                .body(body(memory.list(conversationId, true)));
    }

    private static Map<String, Object> body(List<MemoryEntry> entries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", entries.size());
        body.put("memories", entries.stream().map(MemoryView::of).toList());
        return body;
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
