package io.github.likeelysia.formalagent.cli;

import io.github.likeelysia.formalagent.TalkToTextbookApplication;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.extract.CheckpointIngestService;
import io.github.likeelysia.formalagent.extract.IngestProgress;
import io.github.likeelysia.formalagent.knowledge.KnowledgeStore;
import io.github.likeelysia.formalagent.knowledge.SourceResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 命令行入口:知识库的「灌 / 看 / 删」。
 *
 * <pre>
 *   IngestMain --list                          列出各来源及条数
 *   IngestMain --progress                      看灌库断点(哪本书灌到第几页)
 *   IngestMain &lt;教材文件&gt; [--replace] [--restart]   导入(默认带断点:每 40 页落一次盘)
 *   IngestMain --remove-source &lt;来源名&gt;        按来源删除(可只写一部分)
 * </pre>
 *
 * <p><b>断点怎么用</b>:正常跑就行;中途挂了(断网 / 关窗口 / 限流),<b>重跑同一条命令</b>即从
 * 上一个完成的块继续,已灌过的页不会重新 OCR、不会重新提炼。要推倒重来加 {@code --restart}。
 *
 * <p><b>怎么跑</b>:走 classpath(IDE 里 Run、或 {@code java -cp "target\classes;依赖..." ...cli.IngestMain})。
 * 它是<b>带 Spring Boot 的命令行</b>({@code WebApplicationType.NONE},不起 Tomcat),所以
 * {@code application.yml} / 环境变量 {@code FA_*} 照常生效 —— 这点很关键:
 * 早先用"裸 Spring 容器"起,读不到 yml,{@code fa.*} 全是 null,一启动就 NPE。
 *
 * <p><b>⚠️ 别和应用同时改同一个知识库</b>:网页/接口那个进程把条目全放内存里,
 * 它下一次写盘会把本命令的改动整份盖回去。应用正在跑时请改用 REST:
 * {@code POST /api/ingest?replace=true} 与 {@code DELETE /api/knowledge/source?name=...}。
 */
public class IngestMain {

    private static final String USAGE = """
            用法:
              IngestMain --list                          列出知识库各来源及条数
              IngestMain --progress                      查看灌库断点(哪本书灌到第几页)
              IngestMain <教材文件>...                    导入(默认带断点:每 40 页落一次盘)
              IngestMain --replace <教材文件>             重灌:先清掉该来源旧条目,再导入
              IngestMain --restart <教材文件>             忽略已有断点,从第 1 页重来
              IngestMain --remove-source <来源名>         按来源删除(可只写文件名的一部分)
            说明:
              · 断点粒度 = fa.extract.block-size(默认 40 页),进度文件在 fa.checkpoint.dir(默认 data/checkpoints)
              · 中途挂掉:重跑同一条命令即可续灌,已完成的页不会再 OCR / 再提炼
            """;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.print(USAGE);
            return;
        }

        // --- 解析:开关可以写在文件前或后,顺序随意 ---
        List<String> files = new ArrayList<>();
        boolean replace = false;
        boolean restart = false;
        boolean list = false;
        boolean progress = false;
        String removeSource = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--replace", "-r" -> replace = true;
                    case "--restart" -> restart = true;
                    case "--list", "-l" -> list = true;
                    case "--progress", "-p" -> progress = true;
                    case "--help", "-h" -> {
                        System.out.print(USAGE);
                        return;
                    }
                    case "--remove-source", "-rm" -> {
                        if (i + 1 >= args.length || args[i + 1].isBlank()) {
                            throw new AgentException("--remove-source 后面要跟来源名。\n" + USAGE);
                        }
                        removeSource = args[++i];
                    }
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new AgentException("认不出的参数:" + args[i] + "\n" + USAGE);
                        }
                        files.add(args[i]);
                    }
                }
            }
        } catch (AgentException e) {
            System.out.println("[出错] " + e.getMessage());
            return;
        }

        if (!list && !progress && removeSource == null && files.isEmpty()) {
            System.out.print(USAGE);
            return;
        }

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TalkToTextbookApplication.class)
                .web(WebApplicationType.NONE)          // 命令行工具:不要 Web 容器
                .bannerMode(Banner.Mode.OFF)           // 别刷 Boot 那个大 LOGO
                .logStartupInfo(false)
                .run(args)) {

            KnowledgeStore store = ctx.getBean(KnowledgeStore.class);
            CheckpointIngestService ingest = ctx.getBean(CheckpointIngestService.class);

            if (list) {
                list(store);
                return;
            }
            if (progress) {
                progress(ingest);
                return;
            }
            if (removeSource != null) {
                removeSource(store, removeSource);
                return;
            }
            for (String f : files) {
                ingestFile(ingest, store, f, replace, restart);
            }
        } catch (AgentException e) {
            System.out.println("[出错] " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 动作

    private static void list(KnowledgeStore store) {
        Map<String, Integer> counts = store.countsBySource();
        if (counts.isEmpty()) {
            System.out.println("知识库是空的。");
            return;
        }
        System.out.println("知识库共 " + store.count() + " 条,来自 " + counts.size() + " 个来源:");
        counts.forEach((source, n) -> System.out.println("  " + source + " —— " + n + " 条"));
    }

    private static void progress(CheckpointIngestService ingest) {
        List<IngestProgress> all = ingest.allProgress();
        if (all.isEmpty()) {
            System.out.println("没有断点记录(没有灌到一半的书)。");
            return;
        }
        System.out.println("共 " + all.size() + " 个断点(每 " + ingest.blockSize() + " 页一个):");
        for (IngestProgress p : all) {
            String state = p.completedCleanly() ? "已完成" : (p.finished() ? "页已跑完(有漏页)" : "未完成");
            System.out.println("  [" + state + "] " + p.source() + " —— " + p.donePages() + "/"
                    + p.totalPages() + " 页,已入库 " + p.itemCount() + " 条"
                    + (p.failedChunks() > 0 ? ",提炼失败跳过 " + p.failedChunks() + " 块" : "")
                    + (p.failedPages().isEmpty() ? "" : ",没灌成功的页 " + p.failedPages() + "(重跑即补灌)")
                    + "(更新于 " + p.updatedAt() + ")");
        }
    }

    private static void removeSource(KnowledgeStore store, String nameOrPart) {
        String source = SourceResolver.resolve(store, nameOrPart);     // 先核对,别删错书
        int removed = store.removeBySource(source);
        System.out.println("已删除来源 \"" + source + "\" 的 " + removed
                + " 条 → 知识库现有 " + store.count() + " 条");
    }

    private static void ingestFile(CheckpointIngestService ingest, KnowledgeStore store,
                                   String fileArg, boolean replace, boolean restart) {
        Path file = Path.of(fileArg);
        if (!Files.isRegularFile(file)) {
            throw new AgentException("文件不存在:" + file.toAbsolutePath());
        }
        IngestProgress p = ingest.ingest(file, null, replace, restart);
        System.out.println((replace ? "已重灌 " : "已导入 ") + p.source() + ":"
                + p.donePages() + "/" + p.totalPages() + " 页,本次累计入库 " + p.itemCount()
                + " 条 → 知识库现有 " + store.all().size() + " 条"
                + (p.failedPages().isEmpty() ? "" : ";⚠ 还有 " + p.failedPages().size()
                        + " 页没灌成功 " + p.failedPages() + "(重跑同一条命令会自动补灌)"));
    }
}
