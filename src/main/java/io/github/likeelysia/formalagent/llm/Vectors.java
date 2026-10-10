package io.github.likeelysia.formalagent.llm;

/**
 * 向量小工具:余弦相似度这种「到处都要用一点点」的数学,集中放一处。
 *
 * <p>以前知识库检索里自己写了一份 —— 现在长期记忆也要比"像不像",
 * 再抄一份就成了两份要同步维护的代码,索性提出来。
 */
public final class Vectors {

    private Vectors() {
    }

    /**
     * 余弦相似度:-1 ~ 1,越接近 1 越像(方向越一致)。
     *
     * <p>用余弦而不是欧氏距离,是因为<b>只看方向、不看长短</b> ——
     * "这句话很长"不该让相似度变低(embedding 的模长常和文本长度相关)。
     *
     * <p>任一向量为 null / 全零时返回 0:没向量就是"没法比",别硬算出一堆 NaN。
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
