package com.polar.domain;

import java.util.List;

/**
 * 偏振测量约定：进入指纹的坐标基准。
 * stokesOrder 给出四个 Stokes 分量的存储顺序（基准名 S0..S3）。
 * vSign 控制 V=S3 的符号约定；rotationSign 控制角度正向（旋转矩阵正弦项）。
 */
public record Conventions(
        List<String> stokesOrder,
        int vSign,
        int rotationSign,
        String vecOrder) {

    public static Conventions standard() {
        return new Conventions(List.of("S0", "S1", "S2", "S3"), +1, +1, "S_G_outer_S_A,rowMajor");
    }

    public static Conventions swappedS2S3() {
        return new Conventions(List.of("S0", "S1", "S3", "S2"), +1, +1, "S_G_outer_S_A,rowMajor");
    }

    public int[] permutationFromStandard() {
        int[] perm = new int[4];
        for (int i = 0; i < 4; i++) {
            perm[i] = stokesOrder.indexOf("S" + i);
        }
        return perm;
    }

    public Conventions requireValid() {
        if (stokesOrder.size() != 4
                || stokesOrder.containsAll(List.of("S0", "S1", "S2", "S3")) == false
                || Math.abs(vSign) != 1 || Math.abs(rotationSign) != 1) {
            throw new IllegalArgumentException("非法偏振约定: " + this);
        }
        return this;
    }
}
