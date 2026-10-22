package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 小样本召回标定:不是拍脑袋定一个阈值,而是在一组"相关/不相关"样例上扫一遍,
 * 看默认值 0.30 是否落在合理区间。
 *
 * <p>这是确定性样例(不调真模型);真实 embedding 的标定应另跑一份带人工标注的集。
 */
@DisplayName("记忆召回阈值标定")
class MemoryRecallCalibrationTest {

    record Sample(String query, String memory, boolean relevant) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample("我在准备 Java 实习", "用户在准备 Java 实习", true),
            new Sample("回答请给出处", "用户要求回答给出处", true),
            new Sample("我在学物理", "用户在学物理", true),
            new Sample("我养了一只猫", "用户养了一只猫", true),
            new Sample("我正在准备考研", "用户准备考研", true),
            new Sample("今天天气怎么样", "用户在学物理", false),
            new Sample("推荐一部电影", "用户要求回答给出处", false),
            new Sample("Java 怎么学", "用户养了一只猫", false));

    @Test
    @DisplayName("默认 0.30 在小样本上精确率/召回率都不差")
    void defaultThresholdPerformsWellOnSample() {
        Metrics atDefault = evaluate(0.30);

        assertThat(atDefault.precision()).isGreaterThanOrEqualTo(0.9);
        assertThat(atDefault.recall()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    @DisplayName("扫一遍阈值:最佳 F1 落在 0.1~0.6,不是极端值")
    void bestThresholdIsInAReasonableRange() {
        double bestF1 = -1;
        for (double t = 0.05; t <= 0.90; t += 0.05) {
            bestF1 = Math.max(bestF1, evaluate(t).f1());
        }
        // 同样好的 F1 下取更高的阈值(更保守),避免把"能到 0.05"当成最佳
        double bestThreshold = 0;
        for (double t = 0.90; t >= 0.05; t -= 0.05) {
            if (evaluate(t).f1() >= bestF1 - 1e-9) {
                bestThreshold = t;
                break;
            }
        }

        assertThat(bestF1).isGreaterThanOrEqualTo(0.9);
        assertThat(bestThreshold).isBetween(0.10, 0.60);
    }

    private static Metrics evaluate(double threshold) {
        int tp = 0;
        int fp = 0;
        int fn = 0;
        for (Sample sample : SAMPLES) {
            double score = LongTermMemory.keywordScore(sample.query(), sample.memory());
            boolean predicted = score >= threshold;
            if (predicted && sample.relevant()) {
                tp++;
            } else if (predicted) {
                fp++;
            } else if (sample.relevant()) {
                fn++;
            }
        }
        double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new Metrics(precision, recall, f1);
    }

    record Metrics(double precision, double recall, double f1) {
    }
}
