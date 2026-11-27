package io.github.likeelysia.formalagent.vocab;

import io.github.likeelysia.formalagent.exception.AgentException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import io.github.likeelysia.formalagent.web.CurrentUser;
import io.github.likeelysia.formalagent.usage.UsageService;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private UsageService usage;

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
    public record QueueRequest(List<String> ids, Integer day) { }
    public record PlanRequest(Integer days) { }
    public record StarRequest(boolean starred) { }
    public record ReviewRequest(String rating) { }

    @PostMapping("/explain")
    public VocabService.Explanation explain(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @RequestBody ExplainRequest request) {
        String owner = owner(auth, expectedOwner);
        if (usage != null) usage.check(owner, currentUser.admin(auth), 1);
        VocabService.Explanation explanation = null;
        try {
            explanation = explanations.explain(owner, request.word(), request.level(), request.example());
            return explanation;
        } finally {
            if (usage != null) {
                try {
                    usage.record(owner, 1, request.word() == null ? null : request.word().w(),
                            explanation == null ? null : explanation.cn() + " " + explanation.en());
                } catch (RuntimeException ignored) {
                    // 记账失败不阻断用户拿到解释
                }
            }
        }
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

    /** 只有用户明确勾选并确认的词条才进入今日复习。 */
    @PostMapping("/book/review-queue")
    public VocabNotebook.Book queueForReview(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @RequestBody QueueRequest request) {
        if (request == null) throw new AgentException("请选择要加入今日复习的词条");
        return notebooks.of(owner(auth, expectedOwner)).queueForReview(request.ids(),
                request.day() == null ? 0 : request.day());
    }

    @GetMapping("/review-plan")
    public VocabNotebook.Plan reviewPlan(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner", required = false) String expectedOwner) {
        return notebooks.of(owner(auth, expectedOwner)).plan();
    }

    @PostMapping("/review-plan")
    public VocabNotebook.Plan createReviewPlan(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @RequestBody PlanRequest request) {
        if (request == null || request.days() == null) throw new AgentException("请填写计划天数");
        return notebooks.of(owner(auth, expectedOwner)).createPlan(request.days());
    }

    @PostMapping("/book/{id}/star")
    public VocabNotebook.Entry star(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @PathVariable String id, @RequestBody StarRequest request) {
        return notebooks.of(owner(auth, expectedOwner)).setStarred(id,
                request != null && request.starred());
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

    /** Excel 分批读取；前端每批调用 extract，避免整张表生成一个超大字符串。 */
    @PostMapping(value = "/import/excel-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VocabDocumentImporter.SheetBatch importExcelBatch(Authentication auth,
            @RequestHeader(value = "X-Vocab-Owner") String expectedOwner,
            @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @org.springframework.web.bind.annotation.RequestParam(value = "offset", defaultValue = "0") int offset,
            @org.springframework.web.bind.annotation.RequestParam(value = "limit", defaultValue = "1000") int limit) {
        owner(auth, expectedOwner);
        return importer.readSheetBatch(file, offset, limit);
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
