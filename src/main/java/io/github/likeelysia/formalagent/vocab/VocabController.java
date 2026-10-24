package io.github.likeelysia.formalagent.vocab;

import io.github.likeelysia.formalagent.exception.AgentException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import java.util.Map;

/**
 * 背词工具接口。
 *
 * <p>前端是独立页面 {@code /vocab.html}（和教材问答分开，互不影响）。
 * 两个接口都收 JSON：
 * <pre>
 *   POST /api/vocab/extract  {"article":"..."}
 *   POST /api/vocab/docx     {"article":"...","mode":"cn|en"}
 * </pre>
 */
@RestController
@RequestMapping("/api/vocab")
public class VocabController {

    private final VocabService service;
    private final VocabExplanationService explanations;
    private final VocabNotebook notebook;

    public VocabController(VocabService service, VocabExplanationService explanations, VocabNotebook notebook) {
        this.service = service;
        this.explanations = explanations;
        this.notebook = notebook;
    }

    public record ArticleRequest(String article) {
    }

    public record DocxRequest(String article, String mode, List<VocabService.Word> words) {
    }
    public record ExplainRequest(VocabService.Word word, String level, String example) { }
    public record SaveRequest(List<VocabService.Word> words) { }
    public record ReviewRequest(String rating) { }

    @PostMapping("/explain")
    public VocabService.Explanation explain(@RequestBody ExplainRequest request) {
        return explanations.explain(request.word(), request.level(), request.example());
    }

    @GetMapping("/book")
    public VocabNotebook.Book book() { return notebook.list(); }

    @PostMapping("/book")
    public VocabNotebook.Book save(@RequestBody SaveRequest request) { return notebook.save(request.words()); }

    @PatchMapping("/book/{id}")
    public VocabNotebook.Entry update(@PathVariable String id, @RequestBody VocabService.Word word) { return notebook.update(id, word); }

    @DeleteMapping("/book/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) { notebook.delete(id); return Map.of("removed", true); }

    @PostMapping("/book/{id}/review")
    public VocabNotebook.Entry review(@PathVariable String id, @RequestBody ReviewRequest request) { return notebook.review(id, request.rating()); }

    /** 抽词 + 查词典，返回给页面渲染表格。 */
    @PostMapping("/extract")
    public VocabService.ExtractResult extract(@RequestBody ArticleRequest request) {
        if (request == null || request.article() == null || request.article().isBlank()) {
            throw new AgentException("请先粘贴文章");
        }
        return service.extract(request.article());
    }

    /** 生成 Word 并作为附件下载。 */
    @PostMapping("/docx")
    public ResponseEntity<byte[]> docx(@RequestBody DocxRequest request) throws Exception {
        if (request == null) throw new AgentException("请选择要导出的单词");
        String mode = request.mode() == null ? "cn" : request.mode();
        if (!List.of("cn", "en").contains(mode)) throw new AgentException("释义模式不正确");
        byte[] bytes = request.words() != null ? service.docx(request.words(), mode) : service.docx(request.article(), mode);
        String filename = "en".equalsIgnoreCase(mode) ? "wordlist-en.docx" : "wordlist-cn.docx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }
}
