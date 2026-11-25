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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Single-user study archive, separate from decaying Agent memories. Fail closed on corrupt files. */
@Component
public class VocabNotebook {
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_BULK_WORDS = 5000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_PLAN_DAYS = 180;
    public static final int MAX_DAY_WORDS = 200;
    private final ObjectMapper mapper;
    private final Path file;
    private final Path planFile;
    private final Clock clock;

    public record Review(long at, String rating) { }
    public record Entry(String id, VocabService.Word word, long savedAt, long dueAt, int streak,
                        int reviewCount, List<Review> history, long queuedAt, int planDay,
                        boolean starred) { }
    public record Plan(int days, long createdAt) { }
    public record Book(List<Entry> entries, long now) { }
    private record Saved(List<Entry> entries) { }

    @Autowired
    public VocabNotebook(ObjectMapper mapper, @Value("${fa.vocab.notebook-file:data/vocab/notebook.json}") String file) {
        this(mapper, Path.of(file), Clock.systemUTC());
    }
    VocabNotebook(ObjectMapper mapper, Path file, Clock clock) {
        this.mapper = mapper;
        this.file = file;
        Path parent = file.toAbsolutePath().getParent();
        this.planFile = parent == null ? Path.of("review-plan.json") : parent.resolve("review-plan.json");
        this.clock = clock;
    }

    public synchronized Book list() { return new Book(List.copyOf(read()), clock.millis()); }

    public synchronized Book save(List<VocabService.Word> words) {
        return saveValidated(words, 100);
    }

    /** 一键保存：整批校验、去重和容量检查后只写一次，失败时不会存入一半。 */
    public synchronized Book saveBulk(List<VocabService.Word> words) {
        return saveValidated(words, MAX_BULK_WORDS);
    }

    private Book saveValidated(List<VocabService.Word> words, int limit) {
        if (words == null || words.isEmpty() || words.size() > limit)
            throw new AgentException("每次请选择 1–" + limit + " 个单词加入生词本；生词本容量为 " + MAX_ENTRIES + " 条");
        words.forEach(VocabService::validateWord);
        List<Entry> entries = read();
        int originalSize = entries.size();
        // 为同词建立索引，避免几千词逐条扫描整本生词本。
        var index = new java.util.HashMap<Meaning, Integer>();
        for (int i = 0; i < entries.size(); i++) index.putIfAbsent(meaning(entries.get(i).word()), i);
        for (var word : words) {
            Meaning key = meaning(word);
            Integer found = index.get(key);
            if (found == null) {
                index.put(key, entries.size());
                entries.add(new Entry(UUID.randomUUID().toString(), word, clock.millis(), clock.millis(), 0, 0, List.of(), 0, 0, false));
            } else {
                Entry old = entries.get(found);
                entries.set(found, new Entry(old.id(), mergeWord(old.word(), word), old.savedAt(), old.dueAt(),
                        old.streak(), old.reviewCount(), old.history(), old.queuedAt(), old.planDay(), old.starred()));
            }
        }
        if (entries.size() > MAX_ENTRIES) throw new AgentException("生词本最多保存 " + MAX_ENTRIES + " 条，请先整理或删除旧条目");
        // 新条目仍放在最前，与旧版顺序保持一致；同一批只重排一次。
        List<Entry> ordered = new ArrayList<>(entries.size());
        for (int i = entries.size() - 1; i >= originalSize; i--) ordered.add(entries.get(i));
        ordered.addAll(entries.subList(0, originalSize));
        entries = ordered;
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
            Entry updated = new Entry(id, word, old.savedAt(), old.dueAt(), old.streak(), old.reviewCount(), old.history(), old.queuedAt(), old.planDay(), old.starred());
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
                    old.reviewCount() + 1, List.copyOf(history), 0, 0, old.starred());
            entries.set(i, updated); write(entries); return updated;
        }
        throw new AgentException("生词条目不存在，请刷新");
    }

    /** 将用户明确勾选的条目加入今日复习；复习提交后自动移出队列。 */
    public synchronized Book queueForReview(List<String> ids) {
        return queueForReview(ids, 0);
    }

    /** day=0 表示直接加入今日复习；1..计划天数表示加入第 N 天。 */
    public synchronized Book queueForReview(List<String> ids, int day) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_ENTRIES)
            throw new AgentException("请选择 1–" + MAX_ENTRIES + " 个词条加入今日复习");
        if (day < 0 || day > MAX_PLAN_DAYS) throw new AgentException("复习天数不正确");
        Plan plan = plan();
        if (day > 0 && plan.days() < day) throw new AgentException("请先新建至少 " + day + " 天的计划");
        Set<String> wanted = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) throw new AgentException("词条编号不能为空");
            wanted.add(id);
        }
        List<Entry> entries = read();
        int current = 0, additions = 0, found = 0;
        for (Entry entry : entries) {
            if (entry.queuedAt() > 0 && entry.planDay() == day) current++;
        }
        for (Entry entry : entries) {
            if (!wanted.contains(entry.id())) continue;
            found++;
            if (entry.queuedAt() == 0 || entry.planDay() != day) additions++;
        }
        if (found != wanted.size()) throw new AgentException("部分词条不存在，请刷新后重试");
        if (current + additions > MAX_DAY_WORDS)
            throw new AgentException("每天最多 " + MAX_DAY_WORDS + " 个词，当前还能加入 " + Math.max(0, MAX_DAY_WORDS - current) + " 个");
        for (int i = 0; i < entries.size(); i++) {
            Entry old = entries.get(i);
            if (!wanted.contains(old.id())) continue;
            if (old.queuedAt() == 0 || old.planDay() != day)
                entries.set(i, new Entry(old.id(), old.word(), old.savedAt(), old.dueAt(), old.streak(),
                        old.reviewCount(), old.history(), clock.millis(), day, old.starred()));
        }
        write(entries);
        return new Book(List.copyOf(entries), clock.millis());
    }

    /** 标记/取消今日复习卡片上的小星星；刷新后仍保留。 */
    public synchronized Entry setStarred(String id, boolean starred) {
        List<Entry> entries = read();
        for (int i = 0; i < entries.size(); i++) {
            Entry old = entries.get(i);
            if (!old.id().equals(id)) continue;
            Entry updated = new Entry(old.id(), old.word(), old.savedAt(), old.dueAt(), old.streak(),
                    old.reviewCount(), old.history(), old.queuedAt(), old.planDay(), starred);
            entries.set(i, updated); write(entries); return updated;
        }
        throw new AgentException("生词条目不存在，请刷新");
    }

    public synchronized Plan plan() {
        if (!Files.exists(planFile)) return new Plan(0, 0);
        try {
            Plan plan = mapper.readValue(Files.readString(planFile), Plan.class);
            if (plan == null || plan.days() < 0 || plan.days() > MAX_PLAN_DAYS) throw new IOException("计划天数不正确");
            return plan;
        } catch (Exception e) {
            throw new AgentException("复习计划读取失败，原文件已保留", e);
        }
    }

    public synchronized Plan createPlan(int days) {
        if (days < 1 || days > MAX_PLAN_DAYS) throw new AgentException("计划天数必须在 1–" + MAX_PLAN_DAYS + " 天");
        int maxUsed = read().stream().filter(e -> e.queuedAt() > 0).mapToInt(Entry::planDay).max().orElse(0);
        if (days < maxUsed) throw new AgentException("已有词条安排在第 " + maxUsed + " 天，计划不能少于 " + maxUsed + " 天");
        Plan plan = new Plan(days, clock.millis());
        try {
            Path parent = planFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "review-plan-", ".tmp");
            Files.write(temp, mapper.writeValueAsBytes(plan));
            try {
                Files.move(temp, planFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, planFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return plan;
        } catch (IOException e) {
            throw new AgentException("复习计划保存失败，请重试", e);
        }
    }

    public synchronized void delete(String id) {
        List<Entry> entries = read();
        if (!entries.removeIf(e -> e.id().equals(id))) throw new AgentException("生词条目不存在，请刷新");
        write(entries);
    }

    private record Meaning(String word) { }
    private Meaning meaning(VocabService.Word word) { return new Meaning(word.w().toLowerCase(Locale.ROOT)); }

    private boolean sameMeaning(VocabService.Word a, VocabService.Word b) {
        return a.w().toLowerCase(Locale.ROOT).equals(b.w().toLowerCase(Locale.ROOT));
    }

    /** 同词合并：保留原条目和进度，把新原句、词形补进去，不重复建条。 */
    private VocabService.Word mergeWord(VocabService.Word old, VocabService.Word incoming) {
        var examples = new java.util.LinkedHashSet<String>();
        examples.addAll(old.examples()); examples.addAll(incoming.examples());
        var forms = new java.util.LinkedHashSet<String>();
        forms.addAll(old.forms()); forms.addAll(incoming.forms());
        return new VocabService.Word(
                old.w(),
                blank(old.ph()) ? incoming.ph() : old.ph(),
                blank(old.cn()) ? incoming.cn() : old.cn(),
                blank(old.en()) ? incoming.en() : old.en(),
                blank(old.tag()) ? incoming.tag() : old.tag(),
                blank(old.pos()) ? incoming.pos() : old.pos(),
                forms.stream().limit(100).toList(),
                Math.max(old.count(), incoming.count()),
                examples.stream().limit(3).toList(),
                blank(old.lemma()) ? incoming.lemma() : old.lemma(),
                blank(old.lookupWord()) ? incoming.lookupWord() : old.lookupWord(),
                "FOUND".equals(incoming.lookupStatus()) ? incoming.lookupStatus() : old.lookupStatus(),
                incoming.explanation() != null ? incoming.explanation() : old.explanation(),
                blank(old.customPos()) ? incoming.customPos() : old.customPos());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private List<Entry> dedupe(List<Entry> entries) {
        var index = new java.util.LinkedHashMap<String, Integer>();
        var unique = new ArrayList<Entry>(entries.size());
        for (Entry entry : entries) {
            String key = meaning(entry.word()).word();
            Integer found = index.get(key);
            if (found == null) {
                index.put(key, unique.size());
                unique.add(entry);
            } else {
                unique.set(found, mergeEntry(unique.get(found), entry));
            }
        }
        return unique;
    }

    private Entry mergeEntry(Entry first, Entry duplicate) {
        var reviews = new java.util.LinkedHashMap<String, Review>();
        for (Review review : first.history()) reviews.put(review.at() + "|" + review.rating(), review);
        for (Review review : duplicate.history()) reviews.put(review.at() + "|" + review.rating(), review);
        List<Review> history = new ArrayList<>(reviews.values());
        history.sort(java.util.Comparator.comparingLong(Review::at));
        if (history.size() > 20) history = new ArrayList<>(history.subList(history.size() - 20, history.size()));
        return new Entry(first.id(), mergeWord(first.word(), duplicate.word()),
                Math.min(first.savedAt(), duplicate.savedAt()), Math.min(first.dueAt(), duplicate.dueAt()),
                Math.max(first.streak(), duplicate.streak()), Math.max(first.reviewCount(), duplicate.reviewCount()),
                List.copyOf(history), first.queuedAt() != 0 ? first.queuedAt() : duplicate.queuedAt(),
                first.planDay() != 0 ? first.planDay() : duplicate.planDay(),
                first.starred() || duplicate.starred());
    }

    private List<Entry> read() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("文件超过大小上限");
            Saved saved = mapper.readValue(Files.readString(file), Saved.class);
            if (saved == null || saved.entries() == null || saved.entries().size() > MAX_ENTRIES) throw new IOException("格式不正确");
            List<Entry> normalized = new ArrayList<>(saved.entries().size());
            for (Entry entry : saved.entries()) {
                if (entry == null || entry.id() == null || entry.history() == null) throw new IOException("条目不完整");
                Entry fixed = capEntry(entry);
                VocabService.validateWord(fixed.word());
                normalized.add(fixed);
            }
            return dedupe(normalized);
        } catch (Exception e) { throw new AgentException("生词本读取失败，原文件已保留；请检查或从备份恢复，避免覆盖旧数据", e); }
    }

    /** 兼容历史上同词合并后超过 3 条例句的数据；读取时截到接口允许的上限。 */
    private Entry capEntry(Entry entry) {
        VocabService.Word w = entry.word();
        if (w == null || w.examples() == null || w.forms() == null) return entry;
        if (w.examples().size() <= 3 && w.forms().size() <= 100) return entry;
        VocabService.Word fixed = new VocabService.Word(w.w(), w.ph(), w.cn(), w.en(), w.tag(), w.pos(),
                w.forms().stream().limit(100).toList(), w.count(), w.examples().stream().limit(3).toList(),
                w.lemma(), w.lookupWord(), w.lookupStatus(), w.explanation(), w.customPos());
        return new Entry(entry.id(), fixed, entry.savedAt(), entry.dueAt(), entry.streak(),
                entry.reviewCount(), entry.history(), entry.queuedAt(), entry.planDay(), entry.starred());
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


