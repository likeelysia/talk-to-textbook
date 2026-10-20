package io.github.likeelysia.formalagent.vocab;

import io.github.likeelysia.formalagent.exception.AgentException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public VocabController(VocabService service) {
        this.service = service;
    }

    public record ArticleRequest(String article) {
    }

    public record DocxRequest(String article, String mode) {
    }

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
        if (request == null || request.article() == null || request.article().isBlank()) {
            throw new AgentException("请先粘贴文章");
        }
        String mode = request.mode() == null ? "cn" : request.mode();
        byte[] bytes = service.docx(request.article(), mode);
        String filename = "en".equalsIgnoreCase(mode) ? "wordlist-en.docx" : "wordlist-cn.docx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }
}
