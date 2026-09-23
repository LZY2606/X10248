package org.polarlab.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 偏振约定：Stokes 排列（stokesOrder）与旋转角度正负（rotationSign）。
 * 这两项进入指纹（fingerprint），任何派生结果都记录它是在哪种约定下算出的。
 */
public record Convention(String stokesOrder, int rotationSign) {
    public static final String[] VALID_ORDERS = {"SIQUV", "ISQUV", "ISUQV"};

    public Convention {
        boolean ok = false;
        for (String o : VALID_ORDERS) if (o.equals(stokesOrder)) ok = true;
        if (!ok) throw new IllegalArgumentException("unknown stokes order: " + stokesOrder);
        if (rotationSign != 1 && rotationSign != -1)
            throw new IllegalArgumentException("rotationSign must be +1 or -1");
    }

    public static Convention standard() { return new Convention("ISQUV", 1); }

    /** 把“当前排列下索引 srcIdx”的值放到标准 ISQUV 排列中。 */
    public int[] permutationToStandard() {
        char[] src = stokesOrder.toCharArray();
        char[] std = "ISQUV".toCharArray();
        int[] perm = new int[4];
        for (int k = 0; k < 4; k++) {
            int idx = -1;
            for (int j = 0; j < 4; j++) if (std[k] == src[j]) idx = j;
            if (idx < 0) throw new IllegalStateException("bad order " + stokesOrder);
            perm[k] = idx;
        }
        return perm;
    }

    public Mat reorderToStandard(Mat m) {
        int[] p = permutationToStandard();
        Mat r = new Mat(4, 4);
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                r.set(i, j, m.get(p[i], p[j]));
        return r;
    }

    public double signedAngleRad(double angleRadStandard) {
        return rotationSign * angleRadStandard;
    }

    public String fingerprint() {
        return "stokes=" + stokesOrder + ";rotSign=" + rotationSign;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stokesOrder", stokesOrder);
        map.put("rotationSign", rotationSign);
        map.put("fingerprint", fingerprint());
        return map;
    }
}
