package com.polar.domain;

import java.util.List;

/**
 * 一组共享波长与协方差的测量。协方差版本与强度版本分离保存（各自有独立来源身份）。
 */
public record MeasurementBatch(
        String batchId,
        List<Measurement> rows,
        double[][] covariance,
        String covarianceUnit,
        Conventions conventions,
        double singularRelThreshold,
        double nearSingularLogThreshold) {

    public int n() { return rows.size(); }
}
