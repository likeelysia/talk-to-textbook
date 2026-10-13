package io.github.likeelysia.formalagent.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 多模态「消息」:一条 user 消息,content 是一个数组(图片块 + 文字块)。
 *
 * <p>这是<b>所有 OpenAI 兼容厂商通用</b>的格式 —— 阿里百炼 / 智谱 / 火山方舟 / 硅基流动 /
 * 本地 Ollama / LM Studio / OpenAI 兼容层都一样。所以它<b>跟具体是哪家无关</b>:
 * 厂商专属字段(如 K2.6 的 {@code thinking})走配置的 {@code fa.vision.extra-body-json},
 * 由客户端拼请求体时合并进来(见 {@code OpenAiCompatibleVisionClient})。
 *
 * <p>空字段不进 JSON({@code NON_NULL}):图片块不该带 {@code "text":null},
 * 文字块也不该带 {@code "image_url":null}。
 */
public record VisionMessage(String role, List<ContentPart> content) {

    /** 一条「先图后字」的 user 消息(图在前,多模态模型对「说的是哪张图」更明确)。 */
    public static VisionMessage ofImageAndText(String imageDataUrl, String text) {
        return new VisionMessage("user", List.of(ContentPart.image(imageDataUrl), ContentPart.text(text)));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentPart(String type, String text,
                              @JsonProperty("image_url") ImageUrl imageUrl) {

        public static ContentPart text(String t) {
            return new ContentPart("text", t, null);
        }

        public static ContentPart image(String dataUrl) {
            return new ContentPart("image_url", null, new ImageUrl(dataUrl));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImageUrl(String url) {
    }
}
