package io.github.likeelysia.formalagent.vocab;

import io.github.likeelysia.formalagent.exception.AgentException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import io.github.likeelysia.formalagent.web.CurrentUser;
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
    private final UserVocabNotebooks notebooks;
    private final CurrentUser currentUser;
    private final VocabDocumentImporter importer;

    public VocabController(VocabService service, VocabExplanationService explanations,
                           UserVocabNotebooks notebooks, CurrentUser currentUser, VocabDocumentImporter importer) {
        this.service = service;
        this.explanations = explanations;
        this.notebooks = notebooks;
        this.currentUser = currentUser;
        this.importer = importer;
    }

    public record ArticleRequest(String article) {
    }

    public record DocxRequest(String article, String mode, List<VocabService.Word> words) {
    }
    public record ExplainRequest(VocabService.Word word, String level, String example) { }
    public record SaveRequest(List<VocabService.Word> words) { }
    public record ReviewRequest(String rating) { }

    @PostMapping("/explain")
    public VocabService.Explanation explain(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @RequestBody ExplainRequest request) {
        return explanations.explain(owner(auth, expectedOwner), request.word(), request.level(), request.example());
    }

    @GetMapping("/book")
    public VocabNotebook.Book book(Authentication auth,
            @RequestHeader(value="X-Vocab-Owner", required=false) String expectedOwner) {
        return notebooks.of(owner(auth, expectedOwner)).list();
    }

    @PostMapping("/book")
    public VocabNotebook.Book save(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @RequestBody SaveRequest request) {
        return notebooks.of(owner(auth, expectedOwner)).save(request.words());
    }

    @PostMapping("/book/bulk")
    public VocabNotebook.Book saveBulk(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @RequestBody SaveRequest request) {
        return notebooks.of(owner(auth, expectedOwner)).saveBulk(request.words());
    }


    @PatchMapping("/book/{id}")
    public VocabNotebook.Entry update(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @PathVariable String id, @RequestBody VocabService.Word word) {
        return notebooks.of(owner(auth, expectedOwner)).update(id, word);
    }

    @DeleteMapping("/book/{id}")
    public Map<String, Boolean> delete(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @PathVariable String id) {
        notebooks.of(owner(auth, expectedOwner)).delete(id);
        return Map.of("removed", true);
    }

    @PostMapping("/book/{id}/review")
    public VocabNotebook.Entry review(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @PathVariable String id, @RequestBody ReviewRequest request) {
        return notebooks.of(owner(auth, expectedOwner)).review(id, request.rating());
    }

    /** 文件只转成文字供预览，用户确认后再调用 extract 查词。 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VocabDocumentImporter.ImportedDocument importDocument(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        owner(auth, expectedOwner);
        return importer.read(file);
    }

    /** 抽词 + 查词典，返回给页面渲染表格。 */
    @PostMapping("/extract")
    public VocabService.ExtractResult extract(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @RequestBody ArticleRequest request) {
        owner(auth, expectedOwner);
        if (request == null || request.article() == null || request.article().isBlank()) {
            throw new AgentException("请先粘贴文章");
        }
        return service.extract(request.article());
    }

    /** 生成 Word 并作为附件下载。 */
    @PostMapping("/docx")
    public ResponseEntity<byte[]> docx(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @RequestBody DocxRequest request) throws Exception {
        owner(auth, expectedOwner);
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
    /** 归属只取登录身份，浏览器旧页面携带的标识仅用于核对。 */
    private String owner(Authentication auth, String expectedOwner) {
        String owner = currentUser.id(auth);
        if (expectedOwner != null && !expectedOwner.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "登录账号已切换，请刷新背词页面");
        }
        return owner;
    }
}
