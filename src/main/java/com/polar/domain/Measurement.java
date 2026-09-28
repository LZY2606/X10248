package com.polar.domain;

import java.util.List;

/**
 * 单条偏振测量：发生器链 G、分析器链 A、原始强度、暗场计数、强度单位、波长原文。
 * 原始表示（角度单位、波长字符串、强度原始值）与规范化派生值同时保留。
 */
public record Measurement(
        String measurementId,
        List<JonesStokes.Element> generator,
        List<JonesStokes.Element> analyzer,
        double rawIntensity,
        double darkCounts,
        String intensityUnit,
        String angleUnit,
        String wavelengthRaw,
        String note) {

    public double correctedIntensity() {
        return rawIntensity - darkCounts;
    }
}
