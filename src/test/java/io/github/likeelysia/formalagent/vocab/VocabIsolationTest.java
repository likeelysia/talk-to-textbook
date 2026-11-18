package io.github.likeelysia.formalagent.vocab;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.account.AccountService;
import io.github.likeelysia.formalagent.web.CurrentUser;
import io.github.likeelysia.formalagent.web.UserAccessFilter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VocabIsolationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private UserVocabNotebooks notebooks(VocabNotebook legacy) {
        AccountService accounts = mock(AccountService.class);
        when(accounts.adminUserId()).thenReturn("admin");
        return new UserVocabNotebooks(mapper, accounts, legacy, directory.resolve("notebook.json").toString());
    }

    private VocabService.Word word() {
        return new VocabService.Word("bank", "", "银行", "a place for money", "", "",
                List.of("bank"), 1, List.of("I put money in the bank."), "", "bank", "FOUND", null);
    }

    @Test void readEditReviewDeleteAndRestartStayInsideTheOwner() {
        var legacy = new VocabNotebook(mapper, directory.resolve("notebook.json").toString());
        var old = legacy.save(List.of(word())).entries().get(0);
        var scoped = notebooks(legacy);
        assertThat(scoped.of("admin").list().entries()).containsExactly(old);
        assertThat(scoped.of("uAlice").list().entries()).isEmpty();
        var alice = scoped.of("uAlice");
        var bob = scoped.of("uBob");
        var entry = alice.save(List.of(word())).entries().get(0);
        bob.save(List.of(word()));
        assertThatThrownBy(() -> bob.delete(entry.id())).hasMessageContaining("不存在");
        assertThatThrownBy(() -> bob.update(entry.id(), word())).hasMessageContaining("不存在");
        assertThatThrownBy(() -> bob.review(entry.id(), "good")).hasMessageContaining("不存在");
        alice.update(entry.id(), word().withExplanation(new VocabService.Explanation("河岸", "land by a river", "手动", "manual")));
        alice.review(entry.id(), "good");
        assertThat(bob.list().entries().get(0).reviewCount()).isZero();
        var restarted = notebooks(new VocabNotebook(mapper, directory.resolve("notebook.json").toString()));
        assertThat(restarted.of("uAlice").list().entries().get(0).reviewCount()).isEqualTo(1);
        assertThat(restarted.of("admin").list().entries()).containsExactly(old);
        restarted.of("uAlice").delete(entry.id());
        assertThat(restarted.of("uBob").list().entries()).hasSize(1);
        assertThat(scoped.of("uAlice")).isSameAs(alice);
        assertThatThrownBy(() -> scoped.of("../admin")).hasMessageContaining("非法");
    }

    @Test void requestsUseLoggedInOwnerAndRejectAnOldPageBeforeWriting() throws Exception {
        var legacy = new VocabNotebook(mapper, directory.resolve("notebook.json").toString());
        var scoped = notebooks(legacy);
        var alice = new UsernamePasswordAuthenticationToken("alice", "unused");
        var bob = new UsernamePasswordAuthenticationToken("bob", "unused");
        CurrentUser current = mock(CurrentUser.class);
        when(current.id(alice)).thenReturn("uAlice");
        when(current.id(bob)).thenReturn("uBob");
        var mvc = MockMvcBuilders.standaloneSetup(new VocabController(
                mock(VocabService.class), mock(VocabExplanationService.class), scoped, current, new VocabDocumentImporter())).build();
        String body = mapper.writeValueAsString(new VocabController.SaveRequest(List.of(word())));
        mvc.perform(post("/api/vocab/book").principal(alice).header("X-Vocab-Owner", "uAlice")
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));
        mvc.perform(get("/api/vocab/book").principal(bob)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0));
        mvc.perform(post("/api/vocab/book").principal(bob).header("X-Vocab-Owner", "uAlice")
                .contentType("application/json").content(body)).andExpect(status().isConflict());
        assertThat(scoped.of("uBob").list().entries()).isEmpty();
        var file = new org.springframework.mock.web.MockMultipartFile("file", "read.txt", "text/plain",
                "The bank is near the river.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/vocab/import").file(file).principal(alice).header("X-Vocab-Owner", "uAlice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("The bank is near the river."));
        mvc.perform(multipart("/api/vocab/import").file(file).principal(bob).header("X-Vocab-Owner", "uAlice"))
                .andExpect(status().isConflict());
        assertThat(scoped.of("uBob").list().entries()).isEmpty();
    }

    @Test void explanationCacheIsSeparatedByOwner() {
        AtomicInteger calls = new AtomicInteger();
        var explanations = new VocabExplanationService((messages, options) -> {
            calls.incrementAndGet();
            return "{\"cn\":\"银行\",\"en\":\"A place for money.\",\"uncertain\":false}";
        }, mapper);
        explanations.explain("uAlice", word(), "simple", "I put money in the bank.");
        explanations.explain("uAlice", word(), "simple", "I put money in the bank.");
        explanations.explain("uBob", word(), "simple", "I put money in the bank.");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void ordinaryUsersCanUseVocabButCannotUseUnknownOrAdministrativeRoutes() throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("alice", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        try {
            for (String[] route : new String[][] {{"GET", "/vocab.html"}, {"GET", "/api/vocab/book"},
                    {"POST", "/api/vocab/extract"}, {"POST", "/api/vocab/docx"},
                    {"POST", "/api/vocab/explain"}, {"POST", "/api/vocab/book"},
                    {"PATCH", "/api/vocab/book/abc-123"}, {"DELETE", "/api/vocab/book/abc-123"},
                    {"POST", "/api/vocab/book/abc-123/review"}}) {
                var request = new MockHttpServletRequest(route[0], route[1]);
                request.setContentType("application/json");
                var response = new MockHttpServletResponse();
                var chain = new MockFilterChain();
                new UserAccessFilter().doFilter(request, response, chain);
                assertThat(chain.getRequest()).isNotNull();
                assertThat(response.getRedirectedUrl()).isNull();
            }
            for (String contentType : new String[]{"multipart/form-data; boundary=test", "application/json"}) {
                var upload = new MockHttpServletRequest("POST", "/api/vocab/import");
                upload.setContentType(contentType);
                var result = new MockHttpServletResponse();
                var chain = new MockFilterChain();
                new UserAccessFilter().doFilter(upload, result, chain);
                assertThat(chain.getRequest() != null).isEqualTo(contentType.startsWith("multipart/"));
            }
            var request = new MockHttpServletRequest("POST", "/api/vocab/admin");
            var response = new MockHttpServletResponse();
            new UserAccessFilter().doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
