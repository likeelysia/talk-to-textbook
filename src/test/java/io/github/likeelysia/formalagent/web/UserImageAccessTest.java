package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.likeelysia.formalagent.agent.AgentResult;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.VisionClient;
import io.github.likeelysia.formalagent.service.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserImageAccessTest {
    private UsernamePasswordAuthenticationToken user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "unused", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
    @Test void onlyImageEndpointAcceptsMultipartForOrdinaryUsers() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(user("alice"));
        try {
            for (String[] route : new String[][] {
                    {"POST","/api/agent","multipart/form-data; boundary=test","yes"},
                    {"POST","/api/agent","application/json","yes"},
                    {"POST","/api/agent/stream","application/json","yes"},
                    {"POST","/api/agent/stream","multipart/form-data; boundary=test","no"},
                    {"GET","/api/agent","multipart/form-data","no"},
                    {"POST","/api/agent","text/plain","no"},
                    {"POST","/api/agent/admin","multipart/form-data","no"}}) {
                var request = new MockHttpServletRequest(route[0],route[1]);
                request.setContentType(route[2]);
                var chain = new MockFilterChain();
                new UserAccessFilter().doFilter(request,new MockHttpServletResponse(),chain);
                assertThat(chain.getRequest()!=null).as(String.join(" ",route)).isEqualTo(route[3].equals("yes"));
            }
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void uploadUsesAuthenticatedScopeAndRejectsOtherUsersConversationBeforeVision() throws Exception {
        var users = mock(UserScopeFactory.class);
        var current = mock(CurrentUser.class);
        var alice = user("alice");
        var bob = user("bob");
        when(current.id(alice)).thenReturn("uAlice"); when(current.id(bob)).thenReturn("uBob");
        var aChat = mock(ConversationService.class); var bChat = mock(ConversationService.class);
        var aAssistant = mock(AssistantService.class); var bAssistant = mock(AssistantService.class);
        when(users.of("uAlice")).thenReturn(new UserScopeFactory.UserData(aChat,null,null,null,aAssistant));
        when(users.of("uBob")).thenReturn(new UserScopeFactory.UserData(bChat,null,null,null,bAssistant));
        when(aChat.requireExisting("alice-chat")).thenReturn("alice-chat");
        when(bChat.requireExisting("alice-chat")).thenThrow(new AgentException("会话不存在"));
        var vision = mock(VisionClient.class); var storage = mock(FileStorage.class);
        when(storage.store(eq("uAlice"), anyString(), any())).thenReturn(Path.of("private-image.jpg"));
        when(vision.ask(any(),anyString())).thenReturn("A triangle with three sides.");
        when(aAssistant.ask(eq("alice-chat"),anyString(),anyString(),anyList()))
                .thenReturn(new AssistantService.Turn(new AgentResult("三角形",List.of(),1),"alice-chat"));
        var controller = new AgentController(mock(AssistantService.class),vision,storage,
                mock(ConversationService.class),users,current);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new UserAccessFilter()).build();
        var file = new MockMultipartFile("files","camera.jpg","image/jpeg",new byte[]{1,2,3});
        try {
            SecurityContextHolder.getContext().setAuthentication(bob);
            mvc.perform(multipart("/api/agent").file(file).param("conversationId","alice-chat").principal(bob))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(vision,storage,aAssistant,bAssistant);
            SecurityContextHolder.getContext().setAuthentication(alice);
            mvc.perform(multipart("/api/agent").file(file).param("conversationId","alice-chat").principal(alice))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("三角形"));
            verify(aAssistant).ask(eq("alice-chat"),contains("A triangle"),anyString(),anyList());
            verifyNoInteractions(bAssistant);
        } finally { SecurityContextHolder.clearContext(); }
    }
}
