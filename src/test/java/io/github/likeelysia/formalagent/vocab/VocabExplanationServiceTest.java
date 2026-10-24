package io.github.likeelysia.formalagent.vocab;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.llm.LlmClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VocabExplanationServiceTest {
    private final VocabService.Word word=new VocabService.Word("bank","","银行","difficult dictionary text","","");
    @Test void callsJsonModelOnlyExplicitlyAndCachesBySenseAndLevel() {
        AtomicInteger calls=new AtomicInteger();
        LlmClient llm=(messages,options)->{calls.incrementAndGet();assertThat(options.jsonMode()).isTrue();assertThat(messages.get(0).getContent()).contains("untrusted","everyday words");return "{\"cn\":\"银行\",\"en\":\"A place where people keep money.\",\"uncertain\":false}";};
        var service=new VocabExplanationService(llm,new ObjectMapper());assertThat(calls.get()).isZero();
        var result=service.explain(word,"simple","I put money in the bank.");assertThat(result.source()).isEqualTo("AI 补充");
        assertThat(result.context()).isEqualTo("I put money in the bank.");
        service.explain(word,"simple","I put money in the bank.");assertThat(calls.get()).isEqualTo(1);
        service.explain(word,"standard","I put money in the bank.");assertThat(calls.get()).isEqualTo(2);
        service.explain(word,"simple","He sat on the bank.");assertThat(calls.get()).isEqualTo(3);
    }
    @Test void incompleteUncertainAndOverlongResponsesAreNotCachedAsSuccess() {
        for(String response:new String[]{"{}","{\"uncertain\":true}","{\"cn\":\"\",\"en\":\"\",\"uncertain\":false}","not json"}) {
            var service=new VocabExplanationService((messages,options)->response,new ObjectMapper());
            assertThatThrownBy(()->service.explain(word,"simple","a bank")).hasMessageContaining("AI");
        }
    }
    @Test void rejectsMissingContextAndInvalidLevelBeforeCallingModel() {
        AtomicInteger calls=new AtomicInteger();var service=new VocabExplanationService((m,o)->{calls.incrementAndGet();return "{}";},new ObjectMapper());
        assertThatThrownBy(()->service.explain(word,"simple","")).hasMessageContaining("原句");
        assertThatThrownBy(()->service.explain(word,"other","a bank")).hasMessageContaining("简单");
        assertThat(calls.get()).isZero();
    }
}
