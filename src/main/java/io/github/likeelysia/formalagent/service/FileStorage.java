package io.github.likeelysia.formalagent.service;

import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 上传文件的"落地"服务:把客户端上传的内容安全地存到服务器上的指定目录。
 *
 * <p><b>为什么要它(安全)</b>:如果接口让客户端"报一个路径,我去读" —— 那就等于
 * 把整台机器的读权交出去(任意文件读取 / 路径遍历)。正规做法是:
 * <b>客户端把文件内容传上来,存哪里由服务端自己定。</b>
 *
 * <p>这里做三件安全事:
 * <ol>
 *   <li><b>清洗文件名</b>:只取文件名部分、剥掉目录与危险字符 —— 防 {@code ../../etc/passwd} 这类路径遍历;</li>
 *   <li><b>扩展名白名单</b>:只接受系统真能处理的类型(pdf / txt / md / 图片);</li>
 *   <li><b>固定目录 + 唯一化命名</b>:落盘前加时间戳前缀;<b>落盘路径 normalize 后必须仍在目录内</b>(双保险)。</li>
 * </ol>
 */
@Component
public class FileStorage {

    private static final Logger log = LoggerFactory.getLogger(FileStorage.class);

    /** 允许上传的扩展名(与 ReaderRegistry 能处理的类型保持一致) */
    private static final Set<String> ALLOWED = Set.of(".pdf", ".txt", ".md", ".png", ".jpg", ".jpeg");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Path baseDir;

    public FileStorage(AppConfig config) {
        this.baseDir = Path.of(config.upload().dir()).toAbsolutePath().normalize();
    }

    /** 落盘;返回服务端的真实路径。 */
    public Path store(String originalName, InputStream content) {
        String safe = sanitize(originalName);
        Path target = baseDir.resolve(safe).normalize();
        if (!target.startsWith(baseDir)) {                       // 双保险:规范化后必须还在目录内
            throw new AgentException("非法文件名:" + originalName);
        }
        try {
            Files.createDirectories(baseDir);
            long bytes = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("上传文件已落盘:{} ({} 字节)", target, bytes);
            return target;
        } catch (IOException e) {
            throw new AgentException("保存上传文件失败:" + e.getMessage(), e);
        }
    }

    /**
     * 清洗文件名:剥掉任何目录成分与危险字符,校验扩展名,再加时间戳前缀。
     * 包私有静态,便于单元测试直接压测"路径遍历"这些恶意输入。
     */
    static String sanitize(String original) {
        if (original == null || original.isBlank()) {
            throw new AgentException("文件名为空");
        }
        // 1) 统一分隔符后只取最后一段 —— 客户端夹带的 ../ 或绝对路径在这里被彻底砍掉
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // 2) 去掉控制字符与各系统非法字符
        name = name.replaceAll("\\p{Cntrl}", "")
                .replaceAll("[<>:\"/\\\\|?*]", "_")
                .strip();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            throw new AgentException("非法文件名");
        }
        // 3) 扩展名白名单
        String lower = name.toLowerCase(Locale.ROOT);
        String ext = lower.contains(".") ? lower.substring(lower.lastIndexOf('.')) : "";
        if (!ALLOWED.contains(ext)) {
            throw new AgentException("不支持的文件类型 \"" + ext + "\"(仅支持 " + ALLOWED + ")");
        }
        // 4) 唯一化:时间戳 + 随机 UUID，避免不同用户同秒上传同名图片时互相覆盖
        return LocalDateTime.now().format(STAMP) + "-" + java.util.UUID.randomUUID() + "-" + name;
    }
}
