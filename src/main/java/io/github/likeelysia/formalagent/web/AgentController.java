package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.agent.AgentResult;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.VisionClient;
import io.github.likeelysia.formalagent.prompt.Prompts;
import io.github.likeelysia.formalagent.service.AssistantService;
import io.github.likeelysia.formalagent.service.FileStorage;
import io.github.likeelysia.formalagent.service.UserScopeFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

/**
 * Agent 问答接口:POST /api/agent
 *
 * <p>支持两种调用:
 * <ul>
 *   <li>{@code application/json} —— {@code {"question":"..."}} 纯文本提问;</li>
 *   <li>{@code multipart/form-data} —— 上传<b>图片</b>(最多 {@value #MAX_IMAGES} 张,可选再带 {@code question});
 *       逐张让视觉模型"看懂",按序号合并成一段文字,再交给 Agent 去查知识库回答。</li>
 * </ul>
 *
 * <p>和以前的"固定流水线"不同:这里把"工具"交给模型,由它自己决定查不查、查几次。
 * 返回里带上 {@code trace}(它调了哪些工具),方便观察它到底"想了什么"。
 *
 * <p><b>为什么逐张识别、而不是一次把多张图塞给模型:</b>
 * <ol>
 *   <li>视觉侧组织并发上限=1,逐张本来就是串行排队;</li>
 *   <li>单张失败可以跳过,不至于整批报废;</li>
 *   <li>N 张图的 base64 塞进同一个请求体会让体积膨胀到十几 MB,超时风险大。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** 一次最多允许多少张图片。前端也按这个数做限制,这里是服务端兜底。 */
    public static final int MAX_IMAGES = 10;

    private final AssistantService assistant;
    private final VisionClient vision;
    private final FileStorage fileStorage;
    private final ConversationService conversations;
    private final UserScopeFactory users;
    private final CurrentUser currentUser;

    public AgentController(AssistantService assistant, VisionClient vision, FileStorage fileStorage,
                           ConversationService conversations) {
        this(assistant, vision, fileStorage, conversations, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentController(AssistantService assistant, VisionClient vision, FileStorage fileStorage,
                           ConversationService conversations, UserScopeFactory users,
                           CurrentUser currentUser) {
        this.assistant = assistant;
        this.vision = vision;
        this.fileStorage = fileStorage;
        this.conversations = conversations;
        this.users = users;
        this.currentUser = currentUser;
    }

    private AssistantService assistant(Authentication auth) {
        return users == null ? assistant : users.of(currentUser.id(auth)).assistant();
    }

    private ConversationService conversations(Authentication auth) {
        return users == null ? conversations : users.of(currentUser.id(auth)).conversations();
    }

    /**
     * 请求体:{"question":"...","conversationId":"default"}。
     *
     * <p>{@code conversationId} 可以不传 —— 不传就落到「默认对话」,老前端不用改。
     */
    public record AgentRequest(String question, String conversationId, boolean webSearch) {
        public AgentRequest(String question, String conversationId) {
            this(question, conversationId, false);
        }
    }

    /** 响应体:{"answer":"...【出处】...","steps":2,"trace":[...],"conversationId":"default"}。 */
    public record AgentResponse(String answer, int steps, List<String> trace, String conversationId) {
    }

    /** 纯文本提问。 */
    @PostMapping(value = "/agent", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AgentResponse agent(Authentication auth, @RequestBody AgentRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new AgentException("question 不能为空");
        }
        AssistantService scoped = assistant(auth);
        if (!req.webSearch()) return ask(scoped, req.conversationId(), req.question());
        AssistantService.Turn turn = scoped.ask(req.conversationId(), req.question(), true);
        return response(turn);
    }

    public AgentResponse agent(AgentRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new AgentException("question 不能为空");
        }
        if (!req.webSearch()) return ask(assistant, req.conversationId(), req.question());
        return response(assistant.ask(req.conversationId(), req.question(), true));
    }

    /** 已发送图片：只允许本人访问；文件名不可猜测，仍做归属校验。 */
    @GetMapping("/images/{owner}/{name:.+}")
    public ResponseEntity<Resource> image(Authentication auth,
                                          @PathVariable String owner,
                                          @PathVariable String name) {
        if (currentUser == null || !currentUser.id(auth).equals(owner))
            throw new AgentException("图片不存在");
        Path path = fileStorage.resolve(owner, name);
        MediaType type = MediaTypeFactory.getMediaType(path.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(path));
    }

    /**
     * 图片提问:上传 1~{@value #MAX_IMAGES} 张图片 →(逐张视觉识别)→ 合并成一段文字 → 交给 Agent 回答。
     *
     * @param files          图片,字段名统一为 {@code files}(可重复);上限 {@value #MAX_IMAGES} 张
     * @param question       可选,用户的文字问题;不传则只依据图片内容回答
     * @param conversationId 可选,记到哪个会话;不传 = 默认对话
     */
    @PostMapping(value = "/agent", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentResponse agentWithImage(Authentication auth,
                                        @RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(value = "question", required = false) String question,
                                        @RequestParam(value = "conversationId", required = false) String conversationId) {
        return image(assistant(auth), conversations(auth), files, question, conversationId,
                currentUser == null ? null : currentUser.id(auth));
    }

    public AgentResponse agentWithImage(List<MultipartFile> files, String question,
                                        String conversationId) {
        return image(assistant, conversations, files, question, conversationId, null);
    }

    private AgentResponse image(AssistantService assistant, ConversationService conversations,
                                List<MultipartFile> files, String question, String conversationId,
                                String owner) {
        if (files == null || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new AgentException("files 不能为空");
        }
        if (files.size() > MAX_IMAGES) {
            throw new AgentException("一次最多 " + MAX_IMAGES + " 张图片,当前收到 " + files.size() + " 张");
        }
        // 会话 id 先验掉:识别 10 张图要花两分钟,别等跑完才发现要记到一个不存在的会话里
        String id = conversations.requireExisting(conversationId);

        // 逐张识别,失败的只记一笔、不中断整批；图片先落盘，识别失败也可回看
        StringBuilder imagesText = new StringBuilder();
        List<String> imageUrls = new ArrayList<>();
        int recognized = 0;
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String label = "【第 " + (i + 1) + " 张】";
            if (file.isEmpty()) {
                imagesText.append(label).append("\n(空文件,已跳过)\n");
                continue;
            }
            try {
                Path stored = fileStorage.store(owner, file.getOriginalFilename(), file.getInputStream());
                if (owner != null) {
                    String encodedOwner = UriUtils.encodePathSegment(owner, java.nio.charset.StandardCharsets.UTF_8);
                    String encodedName = UriUtils.encodePathSegment(stored.getFileName().toString(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    imageUrls.add("/api/images/" + encodedOwner + "/" + encodedName);
                }
                String described = vision.ask(stored, Prompts.get("image-question"));
                imagesText.append(label).append('\n').append(described).append('\n');
                recognized++;
            } catch (IOException e) {
                log.warn("第 {} 张图片读取失败: {}", i + 1, e.getMessage());
                imagesText.append(label).append("\n(读取失败,已跳过)\n");
            } catch (RuntimeException e) {
                log.warn("第 {} 张图片识别失败: {}", i + 1, e.getMessage());
                imagesText.append(label).append("\n(识别失败,已跳过)\n");
            }
        }

        boolean hasQuestion = question != null && !question.isBlank();
        if (recognized == 0 && !hasQuestion) {
            throw new AgentException("这几张图片都没能识别出来,请换清晰一些的图片再试");
        }

        String finalQuestion = hasQuestion
                ? question + "\n\n(以下是随附 " + files.size() + " 张图片的内容)\n" + imagesText
                : "请根据下面这些图片的内容回答:\n" + imagesText;
        // 存进历史的用「用户原本那句话 / 几张图」,而不是拼给模型的那一大段 OCR 文本
        String display = hasQuestion ? question : "(" + files.size() + " 张图片)";
        return ask(assistant, id, finalQuestion.trim(), display, imageUrls);
    }

    /** 文本提问入口:模型看到的问题 = 存进历史的问题。 */
    private AgentResponse ask(AssistantService assistant, String conversationId, String question) {
        return ask(assistant, conversationId, question, question, null);
    }

    private AgentResponse ask(AssistantService assistant, String conversationId,
                              String modelQuestion, String displayQuestion, List<String> images) {
        AssistantService.Turn turn = images == null || images.isEmpty()
                ? assistant.ask(conversationId, modelQuestion, displayQuestion)
                : assistant.ask(conversationId, modelQuestion, displayQuestion, images);
        return response(turn);
    }

    private AgentResponse response(AssistantService.Turn turn) {
        AgentResult result = turn.result();
        return new AgentResponse(result.answer(), result.steps(), result.trace(), turn.conversationId());
    }
}
