package io.github.likeelysia.formalagent.vocab;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.account.AccountService;
import io.github.likeelysia.formalagent.web.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VocabBulkSaveTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper();
    private VocabService.Word word(int n) {
        String name="word";do{name+=(char)('a'+n%26);n/=26;}while(n>0);
        return new VocabService.Word(name,"","中文","meaning","","");
    }
    private List<VocabService.Word> words(int start,int count){
        return java.util.stream.IntStream.range(start,start+count).mapToObj(this::word).toList();
    }
    @Test void bulkSavesOver100DeduplicatesAndPreservesReviewProgress() throws Exception {
        var book=new VocabNotebook(mapper,directory.resolve("book.json").toString());
        var saved=book.saveBulk(words(0,150));assertThat(saved.entries()).hasSize(150);
        var reviewed=book.review(saved.entries().get(0).id(),"good");
        var repeated=book.saveBulk(words(0,150));assertThat(repeated.entries()).hasSize(150);
        assertThat(repeated.entries().stream().filter(e->e.id().equals(reviewed.id())).findFirst().orElseThrow()).isEqualTo(reviewed);
    }
    @Test void saves2000InOneClickAndReadsThemAfterRestart() throws Exception {
        Path file=directory.resolve("large.json");
        var book=new VocabNotebook(mapper,file.toString());
        var saved=book.saveBulk(words(0,2000));assertThat(saved.entries()).hasSize(2000);
        var progress=book.review(saved.entries().get(0).id(),"good");
        book.saveBulk(words(0,2000));
        var restarted=new VocabNotebook(mapper,file.toString()).list();
        assertThat(restarted.entries()).hasSize(2000);
        assertThat(restarted.entries().stream().filter(e->e.id().equals(progress.id())).findFirst().orElseThrow()).isEqualTo(progress);
    }

    @Test void capacityAndInvalidInputsNeverPartiallyWrite() throws Exception {
        var file=directory.resolve("book.json");var book=new VocabNotebook(mapper,file.toString());
        book.saveBulk(words(0,5000));book.saveBulk(words(5000,4000));byte[] before=Files.readAllBytes(file);
        assertThatThrownBy(()->book.saveBulk(words(9000,1500))).hasMessageContaining("10000");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        var invalid=new ArrayList<>(words(900,10));invalid.add(new VocabService.Word("", "", "", "", "", ""));
        assertThatThrownBy(()->book.saveBulk(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThatThrownBy(()->book.saveBulk(words(0,5001))).hasMessageContaining("5000");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }
    @Test void ordinaryUserBulkRouteUsesLoggedInOwnerAndRejectsOldPage() throws Exception {
        var accounts=mock(AccountService.class);when(accounts.adminUserId()).thenReturn("admin");
        var legacy=new VocabNotebook(mapper,directory.resolve("legacy.json").toString());
        var scoped=new UserVocabNotebooks(mapper,accounts,legacy,directory.resolve("legacy.json").toString());
        var auth=new UsernamePasswordAuthenticationToken("alice","unused",List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var current=mock(CurrentUser.class);when(current.id(auth)).thenReturn("alice");
        var mvc=MockMvcBuilders.standaloneSetup(new VocabController(mock(VocabService.class),mock(VocabExplanationService.class),scoped,current,new VocabDocumentImporter()))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new UserAccessFilter()).build();
        String body=mapper.writeValueAsString(new VocabController.SaveRequest(words(0,150)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            mvc.perform(post("/api/vocab/book/bulk").principal(auth).header("X-Vocab-Owner","bob").contentType("application/json").content(body)).andExpect(status().isConflict());
            assertThat(scoped.of("alice").list().entries()).isEmpty();
            mvc.perform(post("/api/vocab/book/bulk").principal(auth).header("X-Vocab-Owner","alice").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(150));
            assertThat(scoped.of("bob").list().entries()).isEmpty();assertThat(legacy.list().entries()).isEmpty();
        } finally {SecurityContextHolder.clearContext();}
    }
}
