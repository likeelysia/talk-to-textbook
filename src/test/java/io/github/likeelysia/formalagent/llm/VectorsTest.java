package io.github.likeelysia.formalagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 余弦相似度:只看方向,不看长短;没向量时老老实实返回 0。 */
@DisplayName("Vectors 余弦相似度")
class VectorsTest {

    @Test
    @DisplayName("同方向 = 1(长一点短一点都一样)")
    void sameDirectionIsOne() {
        assertThat(Vectors.cosine(new float[] {1, 2, 3}, new float[] {2, 4, 6})).isCloseTo(1.0, offset(0.0001));
        assertThat(Vectors.cosine(new float[] {1, 0, 0}, new float[] {9, 0, 0})).isCloseTo(1.0, offset(0.0001));
    }

    @Test
    @DisplayName("垂直 = 0,反向 = -1")
    void orthogonalAndOpposite() {
        assertThat(Vectors.cosine(new float[] {1, 0}, new float[] {0, 1})).isCloseTo(0.0, offset(0.0001));
        assertThat(Vectors.cosine(new float[] {1, 0}, new float[] {-1, 0})).isCloseTo(-1.0, offset(0.0001));
    }

    @Test
    @DisplayName("null / 空 / 全零:返回 0,别算出 NaN")
    void degenerateInputs() {
        assertThat(Vectors.cosine(null, new float[] {1})).isZero();
        assertThat(Vectors.cosine(new float[] {1}, null)).isZero();
        assertThat(Vectors.cosine(new float[0], new float[] {1})).isZero();
        assertThat(Vectors.cosine(new float[] {0, 0}, new float[] {1, 1})).isZero();
    }

    @Test
    @DisplayName("长度不一致时只比公共部分(不炸)")
    void differentLengthsUseCommonPrefix() {
        assertThat(Vectors.cosine(new float[] {1}, new float[] {1, 5, 9})).isCloseTo(1.0, offset(0.0001));
    }
}
