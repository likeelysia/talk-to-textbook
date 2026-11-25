package io.github.likeelysia.formalagent.vocab;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VocabNotebookTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-24T02:00:00Z"),ZoneOffset.UTC);
    private VocabService.Word word(String sentence){return new VocabService.Word("bank","","银行","a place for money","","",List.of("bank"),1,List.of(sentence),"","bank","FOUND",null);}
    private VocabNotebook book(){return new VocabNotebook(mapper,temp.resolve("book.json"),clock);}
    @Test void sameWordMergesContextsKeepsOneEntryAndReviewProgress() {
        var book=book();book.save(List.of(word("I keep money in the bank.")));
        var id=book.list().entries().get(0).id();book.review(id,"good");
        book.save(List.of(word("I keep money in the bank."),word("He sat on the river bank.")));
        var restored=book().list();assertThat(restored.entries()).hasSize(1);
        assertThat(restored.entries().get(0).word().examples())
                .contains("I keep money in the bank.","He sat on the river bank.");
        assertThat(restored.entries().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow().reviewCount()).isEqualTo(1);
    }
    @Test void ratingsScheduleWithoutDeletingStudyHistory() {
        var book=book();String id=book.save(List.of(word("a bank"))).entries().get(0).id();
        var good=book.review(id,"good");assertThat(good.dueAt()-clock.millis()).isEqualTo(86_400_000L);
        var twice=book.review(id,"good");assertThat(twice.dueAt()-clock.millis()).isEqualTo(3*86_400_000L);
        var again=book.review(id,"again");assertThat(again.streak()).isZero();assertThat(again.history()).hasSize(3);
        assertThat(again.dueAt()-clock.millis()).isEqualTo(600_000L);
    }
    @Test void manualUpdatePreservesProgressAndCanBeDeleted() {
        var book=book();var entry=book.save(List.of(word("a bank"))).entries().get(0);book.review(entry.id(),"hard");
        var updated=book.update(entry.id(),entry.word().withExplanation(new VocabService.Explanation("银行","A place for money.","手动修改","manual")));
        assertThat(updated.reviewCount()).isEqualTo(1);assertThat(book().list().entries().get(0).word().explanation().source()).isEqualTo("手动修改");
        book.delete(entry.id());assertThat(book().list().entries()).isEmpty();
    }
    @Test void queueIsExplicitAndClearsAfterReview() {
        var book=book();var entry=book.save(List.of(word("a bank"))).entries().get(0);
        assertThat(entry.queuedAt()).isZero();
        var queued=book.queueForReview(List.of(entry.id())).entries().get(0);
        assertThat(queued.queuedAt()).isPositive();
        assertThat(book().list().entries().get(0).queuedAt()).isPositive();
        book.review(entry.id(),"good");
        assertThat(book().list().entries().get(0).queuedAt()).isZero();
        assertThatThrownBy(()->book.queueForReview(List.of("missing"))).hasMessageContaining("不存在");
    }
    @Test void oldNotebookWithoutQueueFieldStillLoads() throws Exception {
        var book=book();book.save(List.of(word("a bank")));
        Path file=temp.resolve("book.json");
        String json=Files.readString(file).replaceAll(",\\s*\"queuedAt\"\\s*:\\s*\\d+","");
        Files.writeString(file,json);
        assertThat(book().list().entries().get(0).queuedAt()).isZero();
    }
    @Test void corruptFileIsNeverSilentlyOverwritten() throws Exception {
        Path file=temp.resolve("book.json");Files.writeString(file,"broken json");
        assertThatThrownBy(()->book().save(List.of(word("a bank")))).hasMessageContaining("原文件已保留");
        assertThat(Files.readString(file)).isEqualTo("broken json");
    }
    @Test void resavingAnImprovedExplanationPreservesReviewProgress() {
        var book=book();var original=word("a bank");var entry=book.save(List.of(original)).entries().get(0);
        book.review(entry.id(),"good");book.save(List.of(original.withExplanation(new VocabService.Explanation("银行","A place for money.","手动修改","manual"))));
        var restored=book().list().entries().get(0);assertThat(restored.reviewCount()).isEqualTo(1);
        assertThat(restored.word().explanation().en()).isEqualTo("A place for money.");
    }
    private VocabService.Word contextless() {
        return new VocabService.Word("bank","","银行","a place for money","","",List.of("bank"),1,List.of(),"","bank","FOUND",null);
    }
    @Test void missingContextCanBeFilledOnceWithoutLosingReviewProgress() {
        var book=book();var entry=book.save(List.of(contextless())).entries().get(0);
        var reviewed=book.review(entry.id(),"good");
        var filled=book.update(entry.id(),word("I keep money in the bank."));
        assertThat(filled.word().examples()).containsExactly("I keep money in the bank.");
        assertThat(filled.reviewCount()).isEqualTo(reviewed.reviewCount());
        assertThat(filled.history()).isEqualTo(reviewed.history());
        assertThat(filled.dueAt()).isEqualTo(reviewed.dueAt());
        assertThat(book().list().entries().get(0).word().examples()).isEqualTo(filled.word().examples());
        assertThatThrownBy(()->book.update(entry.id(),word("He sat on the river bank."))).hasMessageContaining("请保留");
    }
    @Test void customPosAndMeaningPersistWithCustomSource() {
        var book=book();var entry=book.save(List.of(word("a bank"))).entries().get(0);
        var custom=new VocabService.Word("bank","","自定义中文","自定义英文","","",List.of("bank"),1,
                List.of("a bank"),"","bank","FOUND",
                new VocabService.Explanation("自定义中文","自定义英文","自定义","manual","a bank"),"n. 银行");
        book.update(entry.id(),custom);
        var restored=book().list().entries().get(0);
        assertThat(restored.word().customPos()).isEqualTo("n. 银行");
        assertThat(restored.word().explanation().source()).isEqualTo("自定义");
    }
    @Test void starMarkPersistsAndCanBeRemoved() {
        var book=book();var entry=book.save(List.of(word("a bank"))).entries().get(0);
        assertThat(entry.starred()).isFalse();
        assertThat(book.setStarred(entry.id(),true).starred()).isTrue();
        assertThat(book().list().entries().get(0).starred()).isTrue();
        assertThat(book.setStarred(entry.id(),false).starred()).isFalse();
    }
    @Test void planLimitsAndDayAssignments() {
        var book=book();var entry=book.save(List.of(word("a bank"))).entries().get(0);
        assertThatThrownBy(()->book.createPlan(0)).hasMessageContaining("1–180");
        assertThatThrownBy(()->book.createPlan(181)).hasMessageContaining("1–180");
        assertThatThrownBy(()->book.queueForReview(List.of(entry.id()),5)).hasMessageContaining("先新建");
        assertThat(book.createPlan(30).days()).isEqualTo(30);
        assertThatThrownBy(()->book.queueForReview(List.of(entry.id()),31)).hasMessageContaining("先新建");
        assertThat(book.queueForReview(List.of(entry.id()),5).entries().get(0).planDay()).isEqualTo(5);
        assertThat(book.queueForReview(List.of(entry.id()),0).entries().get(0).planDay()).isZero();
        assertThat(book.queueForReview(List.of(entry.id()),7).entries().get(0).planDay()).isEqualTo(7);
        assertThat(book.review(entry.id(),"good").planDay()).isZero();
    }
    @Test void sameWordMergesAtMostThreeExamplesSoNotebookStaysReadable() {
        var book=book();
        for (String sentence : List.of("a bank", "b bank", "c bank", "d bank"))
            book.save(List.of(word(sentence)));
        var restored=book().list().entries();
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).word().examples()).hasSize(3);
    }
    @Test void oldEntryWithTooManyExamplesStillLoadsAndIsCapped() throws Exception {
        Path file=temp.resolve("book.json");
        var w=new VocabService.Word("bank","","银行","a place","","",List.of("bank"),1,
                List.of("a","b","c","d"),"","bank","FOUND",null);
        var e=new VocabNotebook.Entry("id",w,1,2,0,0,List.of(),0,0,false);
        var root=mapper.createObjectNode();root.putArray("entries").addPOJO(e);
        Files.writeString(file,mapper.writeValueAsString(root));
        var loaded=book().list().entries();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).word().examples()).containsExactly("a","b","c");
    }
    @Test void sameWordIsMergedInsteadOfCreatingSecondEntry() {
        var book=book();var empty=book.save(List.of(contextless())).entries().get(0);
        var known=book.save(List.of(word("a bank"))).entries().get(0);
        assertThat(known.id()).isEqualTo(empty.id());
        assertThat(known.word().examples()).contains("a bank");
        assertThat(book().list().entries()).hasSize(1);
        assertThatThrownBy(()->book.update(known.id(),contextless())).hasMessageContaining("请保留");
    }
}
