package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationMeta;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 会话接口的"翻译"是否正确:请求体怎么读、响应体怎么拼。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConversationController")
class ConversationControllerTest {

    @Mock
    private ConversationService service;

    private ConversationController controller;

    @BeforeEach
    void setUp() {
        controller = new ConversationController(service);
    }

    private static ConversationMeta meta(String id, String title) {
        return new ConversationMeta(id, title, id, 1L, 2L, 0);
    }

    @Test
    @DisplayName("列表:带上 defaultId,前端不用猜默认会话叫什么")
    void listCarriesDefaultId() {
        when(service.list()).thenReturn(List.of(meta(Conversation.DEFAULT_ID, "默认对话")));

        Map<String, Object> body = controller.list();

        assertThat(body.get("defaultId")).isEqualTo(Conversation.DEFAULT_ID);
        assertThat((List<?>) body.get("conversations")).hasSize(1);
    }

    @Test
    @DisplayName("读某个会话:原样交给 service")
    void getDelegates() {
        when(service.get("c1")).thenReturn(Conversation.of("c1", "物理"));

        assertThat(controller.get("c1").title()).isEqualTo("物理");
    }

    @Test
    @DisplayName("新建:不传 body 也不会 NPE")
    void createToleratesMissingBody() {
        when(service.create(any())).thenReturn(meta("c1", "新对话"));

        assertThat(controller.create(null).id()).isEqualTo("c1");
        verify(service).create(null);
    }

    @Test
    @DisplayName("新建:带标题就透传")
    void createPassesTitle() {
        when(service.create("物理复习")).thenReturn(meta("c2", "物理复习"));

        assertThat(controller.create(new ConversationController.CreateRequest("物理复习")).title())
                .isEqualTo("物理复习");
    }

    @Test
    @DisplayName("改名:透传 id 和新标题")
    void renameDelegates() {
        when(service.rename("c1", "新名")).thenReturn(meta("c1", "新名"));

        assertThat(controller.rename("c1", new ConversationController.RenameRequest("新名")).title())
                .isEqualTo("新名");
    }

    @Test
    @DisplayName("删除:回 {deleted, count}")
    void deleteReportsCount() {
        when(service.list()).thenReturn(List.of(meta(Conversation.DEFAULT_ID, "默认对话")));

        Map<String, Object> body = controller.delete("c9");

        verify(service).delete("c9");
        assertThat(body.get("deleted")).isEqualTo("c9");
        assertThat(body.get("count")).isEqualTo(1);
    }

    @Test
    @DisplayName("改名:body 为 null 时交给 service 去判空(而不是在这里 NPE)")
    void renameToleratesMissingBody() {
        when(service.rename(anyString(), any())).thenReturn(meta("c1", "x"));

        controller.rename("c1", null);

        verify(service).rename("c1", null);
        verify(service, never()).get(anyString());
    }
}
