package io.github.likeelysia.formalagent.extract;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * 灌库断点(进度记录):一份 JSON,记着「这本书灌到第几页了」。
 *
 * <p>为什么要有它:一本 406 页的扫描件,OCR + 提炼要一两个小时。中途断网、关窗口、
 * 限流打嗝 —— 没有断点就得从第 1 页重来。<b>每完成一块(默认 40 页)落一次盘</b>,
 * 重跑时直接从完成页之后接着灌。
 *
 * <p>与知识库的关系:{@code donePages} 之前的知识点<b>已经在知识库里了</b>(每块都调了
 * {@code store.addAll},而同 id 覆盖 → 重跑一块也不会重复),断点文件只是「索引」,
 * 用来知道从哪继续,不存正文。
 *
 * <p><b>漏页怎么办</b>:OCR 单页失败不会让整本挂,但也不允许"静默丢掉" —— 失败的页号会记进
 * {@code failedPages},下次跑同一条命令时<b>先补灌这几页</b>(补一页落一次盘),补上就划掉。
 * 一直补不上的页也不阻塞,只是留在清单里、在 {@code --progress} 里列出来。
 *
 * @param source       知识来源(书名),和知识库里条的 source 一致
 * @param fileName     源文件名(显示用,不参与匹配)
 * @param fileSize     源文件字节数 —— 文件换了一版(大小变了)旧断点自动作废
 * @param totalPages   总页数 —— 页数变了同理作废
 * @param blockSize    断点粒度(页)
 * @param donePages    已灌完到第几页(1 起;0 = 还没开始)
 * @param itemCount    已入库条数(累计,只用于汇报)
 * @param failedChunks 提炼失败被跳过的块数(累计;>0 说明有内容缺口)
 * @param failedPages  <b>没读成功(OCR 失败)的页号</b> —— 升序去重。开跑前会先补灌这几页,
 *                     补上了就从清单里划掉;一直补不上就一直留着(不阻塞、不死循环)。
 *                     旧断点文件没有这个字段时反序列化成 null → 构造器兜成空表。
 * @param updatedAt    最后更新时间(人看)
 */
public record IngestProgress(
        String source,
        String fileName,
        long fileSize,
        int totalPages,
        int blockSize,
        int donePages,
        int itemCount,
        int failedChunks,
        List<Integer> failedPages,
        String updatedAt) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public IngestProgress {
        failedPages = (failedPages == null) ? List.of() : List.copyOf(failedPages);
    }

    /**
     * 断点文件的键:<b>来源 + 文件名 + 大小</b> 的 SHA-1 前 8 字节(十六进制)。
     *
     * <p>故意用哈希而不是书名当文件名 —— 书名叫「大学物理学(第四版)」,直接当文件名会带来
     * 中文/括号/空格等一堆编码与转义问题;哈希是纯 ASCII,永远不会出事。
     */
    static String keyOf(String source, String fileName, long fileSize) {
        String raw = source + "|" + fileName + "|" + fileSize;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new AgentException("无法计算断点文件名(SHA-1 不可用)", e);
        }
    }

    /** 全新断点(还没灌任何一页)。 */
    static IngestProgress fresh(String source, String fileName, long fileSize, int totalPages, int blockSize) {
        return new IngestProgress(source, fileName, fileSize, totalPages, Math.max(1, blockSize),
                0, 0, 0, List.of(), now());
    }

    /** 完成一块之后的新进度。 */
    IngestProgress afterBlock(int newDonePages, int newItemCount, int newFailedChunks,
                              List<Integer> newFailedPages) {
        return new IngestProgress(source, fileName, fileSize, totalPages, blockSize,
                newDonePages, newItemCount, newFailedChunks,
                newFailedPages == null ? List.of() : newFailedPages, now());
    }

    /** 整本灌完了吗(页都跑过了)。注意:页跑完 ≠ 一页没漏,还要看 {@link #failedPages()}. */
    public boolean finished() {
        return donePages >= totalPages;
    }

    /** 真正干净地完成:页跑完了,而且没有漏页。 */
    public boolean completedCleanly() {
        return finished() && failedPages.isEmpty();
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }
}
