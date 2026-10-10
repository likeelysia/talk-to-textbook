package io.github.likeelysia.formalagent.memory;

import io.github.likeelysia.formalagent.conversation.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 长期记忆的<b>第一道闸</b>:用规则把明显的寒暄、客套、纯确认挡在外面。
 *
 * <p>为什么先来一道规则闸,而不是全交给模型?
 * <ol>
 *   <li><b>省钱</b>:一句"好的"、"谢谢"送到模型那儿打分,纯属白烧 token;</li>
 *   <li><b>稳</b>:规则是确定的,模型是概率的 —— 能一眼判死的就别让它发挥。</li>
 * </ol>
 *
 * <p>第二道闸(打分 {@code keep >= 0.5})在 {@link LlmMemoryExtractor} 里。
 * 两道闸的顺序是"先便宜后贵":规则不要钱,模型要钱。
 */
final class MemoryFilter {

    /** 一句话短于这个字数的一轮,基本没什么可记的(寒暄 / 确认 / 单个词)。 */
    static final int DEFAULT_MIN_CHARS = 20;

    /** 纯寒暄:整句话就是这个,里面没有任何"关于用户的事实"。 */
    private static final Set<String> GREETINGS = Set.of(
            "你好", "您好", "哈喽", "hi", "hello", "hey", "在吗", "在么", "在不在",
            "谢谢", "多谢", "感谢", "thanks", "thankyou", "再见", "拜拜", "bye",
            "早上好", "中午好", "下午好", "晚上好", "晚安");

    /** 纯确认 / 催更:单看这一句没有信息量。 */
    private static final Set<String> ACKS = Set.of(
            "好的", "好", "行", "嗯", "嗯嗯", "哦", "噢", "啊", "继续", "然后", "然后呢",
            "是吗", "对的", "是的", "对", "ok", "okay", "收到", "明白", "懂了", "知道了", "没问题");

    /** 去掉空白和标点:这样"你好!"、"好的。"、"OK~"都能对上上面的表。 */
    private static final String NOISE = "[\\s，。、！？；：、“”‘’（）【】《》〈〉「」『』…·,.!?;:\"'()\\[\\]{}<>~#*_\\-=+/\\\\|]+";

    private MemoryFilter() {
    }

    /**
     * 这一「轮」值不值得送去提炼。
     *
     * <p>判据:两边都得有话说、不是纯寒暄/确认、合起来有点长度。
     * 注意这里<b>不判断"有没有价值"</b> —— 那件事交给模型打分,规则只管"一眼没救的"。
     */
    static boolean worthKeeping(ChatMessage user, ChatMessage assistant, int minChars) {
        if (user == null || assistant == null) {
            return false;
        }
        String question = user.content();
        String answer = assistant.content();
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        int floor = minChars > 0 ? minChars : DEFAULT_MIN_CHARS;
        if (question.strip().length() + answer.strip().length() < floor) {
            return false;
        }
        String plain = plain(question);
        if (plain.isEmpty() || GREETINGS.contains(plain)) {
            return false;
        }
        return !(plain.length() <= 6 && ACKS.contains(plain));
    }

    /**
     * 按「轮」把不值得记的整轮剔掉(用户那条 + 它的回答一起丢),返回的顺序不变。
     *
     * <p>为什么要成对丢:只留半轮(比如留着助手的回答、丢了用户的提问)会让模型看到"凭空冒出来的一段话",
     * 反而容易记错东西。
     */
    static List<ChatMessage> prescreen(List<ChatMessage> messages, int minChars) {
        List<ChatMessage> kept = new ArrayList<>();
        int i = 0;
        while (i < messages.size()) {
            int end = i + 1;
            while (end < messages.size() && !messages.get(end).fromUser()) {
                end++;                                     // 一条用户消息开启一轮,它后面的都算这一轮
            }
            List<ChatMessage> turn = messages.subList(i, end);
            ChatMessage user = turn.stream().filter(ChatMessage::fromUser).findFirst().orElse(null);
            ChatMessage assistant = turn.stream().filter(ChatMessage::fromAssistant).findFirst().orElse(null);
            if (worthKeeping(user, assistant, minChars)) {
                kept.addAll(turn);
            }
            i = end;
        }
        return kept;
    }

    /** 去空白 + 去标点 + 转小写,用来和上面的词表对齐。 */
    private static String plain(String text) {
        return text.replaceAll(NOISE, "").toLowerCase(Locale.ROOT);
    }
}
