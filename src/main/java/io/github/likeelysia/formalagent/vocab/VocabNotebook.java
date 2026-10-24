package io.github.likeelysia.formalagent.vocab;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Single-user study archive, separate from decaying Agent memories. Fail closed on corrupt files. */
@Component
public class VocabNotebook {
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final Path file;
    private final Clock clock;

    public record Review(long at, String rating) { }
    public record Entry(String id, VocabService.Word word, long savedAt, long dueAt, int streak,
                        int reviewCount, List<Review> history) { }
    public record Book(List<Entry> entries, long now) { }
    private record Saved(List<Entry> entries) { }

    @Autowired
    public VocabNotebook(ObjectMapper mapper, @Value("${fa.vocab.notebook-file:data/vocab/notebook.json}") String file) {
        this(mapper, Path.of(file), Clock.systemUTC());
    }
    VocabNotebook(ObjectMapper mapper, Path file, Clock clock) {
        this.mapper = mapper; this.file = file; this.clock = clock;
    }

    public synchronized Book list() { return new Book(List.copyOf(read()), clock.millis()); }

    public synchronized Book save(List<VocabService.Word> words) {
        if (words == null || words.isEmpty() || words.size() > 100)
            throw new AgentException("每次请选择 1–100 个单词加入生词本");
        words.forEach(VocabService::validateWord);
        List<Entry> entries = read();
        for (var word : words) {
            int found = -1;
            for (int i = 0; i < entries.size(); i++) if (sameMeaning(entries.get(i).word(), word)) { found = i; break; }
            if (found < 0) entries.add(0, new Entry(UUID.randomUUID().toString(), word, clock.millis(),
                    clock.millis(), 0, 0, List.of()));
            else if (word.explanation() != null) {
                Entry old = entries.get(found);
                entries.set(found, new Entry(old.id(), word, old.savedAt(), old.dueAt(), old.streak(), old.reviewCount(), old.history()));
            }
        }
        if (entries.size() > MAX_ENTRIES) throw new AgentException("生词本最多保存 " + MAX_ENTRIES + " 条，请先整理或删除旧条目");
        write(entries);
        return new Book(List.copyOf(entries), clock.millis());
    }

    public synchronized Entry update(String id, VocabService.Word word) {
        VocabService.validateWord(word);
        List<Entry> entries = read();
        for (int i = 0; i < entries.size(); i++) {
            Entry old = entries.get(i);
            if (!old.id().equals(id)) continue;
            boolean fillingContext = old.word().examples().stream().allMatch(String::isBlank)
                    && word.examples().size() == 1 && !word.examples().get(0).isBlank();
            if (!old.word().w().equalsIgnoreCase(word.w()) || (!old.word().examples().equals(word.examples()) && !fillingContext))
                throw new AgentException("请保留原单词与来源原句；其他语境可以另存为新条目");
            if (fillingContext && entries.stream().anyMatch(e -> !e.id().equals(id) && sameMeaning(e.word(), word)))
                throw new AgentException("生词本已有同词同原句条目，请使用已有条目");
            Entry updated = new Entry(id, word, old.savedAt(), old.dueAt(), old.streak(), old.reviewCount(), old.history());
            entries.set(i, updated); write(entries); return updated;
        }
        throw new AgentException("生词条目不存在，请刷新");
    }

    public synchronized Entry review(String id, String rating) {
        if (rating == null || !List.of("again", "hard", "good").contains(rating)) throw new AgentException("请选择不会、模糊或掌握");
        List<Entry> entries = read();
        for (int i = 0; i < entries.size(); i++) {
            Entry old = entries.get(i);
            if (!old.id().equals(id)) continue;
            int streak = rating.equals("again") ? 0 : rating.equals("hard") ? old.streak() : Math.min(5, old.streak() + 1);
            long delay = rating.equals("again") ? Duration.ofMinutes(10).toMillis()
                    : rating.equals("hard") ? Duration.ofDays(1).toMillis()
                    : Duration.ofDays(new int[]{1, 1, 3, 7, 14, 30}[streak]).toMillis();
            List<Review> history = new ArrayList<>(old.history());
            history.add(new Review(clock.millis(), rating));
            if (history.size() > 20) history = new ArrayList<>(history.subList(history.size() - 20, history.size()));
            Entry updated = new Entry(id, old.word(), old.savedAt(), clock.millis() + delay, streak,
                    old.reviewCount() + 1, List.copyOf(history));
            entries.set(i, updated); write(entries); return updated;
        }
        throw new AgentException("生词条目不存在，请刷新");
    }

    public synchronized void delete(String id) {
        List<Entry> entries = read();
        if (!entries.removeIf(e -> e.id().equals(id))) throw new AgentException("生词条目不存在，请刷新");
        write(entries);
    }

    private boolean sameMeaning(VocabService.Word a, VocabService.Word b) {
        return a.w().toLowerCase(Locale.ROOT).equals(b.w().toLowerCase(Locale.ROOT)) && a.examples().equals(b.examples());
    }

    private List<Entry> read() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("文件超过大小上限");
            Saved saved = mapper.readValue(Files.readString(file), Saved.class);
            if (saved == null || saved.entries() == null || saved.entries().size() > MAX_ENTRIES) throw new IOException("格式不正确");
            for (Entry entry : saved.entries()) {
                if (entry == null || entry.id() == null || entry.history() == null) throw new IOException("条目不完整");
                VocabService.validateWord(entry.word());
            }
            return new ArrayList<>(saved.entries());
        } catch (Exception e) { throw new AgentException("生词本读取失败，原文件已保留；请检查或从备份恢复，避免覆盖旧数据", e); }
    }

    private void write(List<Entry> entries) {
        Path temp = null;
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Saved(entries));
            if (json.length > MAX_BYTES) throw new IOException("生词本超过 16 MB，请先整理条目");
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, "vocab-", ".tmp");
            Files.write(temp, json);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, file.toAbsolutePath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file.toAbsolutePath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new AgentException("生词本保存失败，未报告保存成功，请重试并检查磁盘", e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
    }
}
