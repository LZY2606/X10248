package com.polarlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the offline domain case pack:
 *
 * case-pack/
 *   README.md            narrative and replay map
 *   manifest.json        machine-readable scenario + step plan
 *   baseline/            initial separated sources
 *   steps/               boundary scenario inputs (singular design, conventions, ...)
 */
public final class CasePack {
    public static final ObjectMapper OM = new ObjectMapper();

    private CasePack() {}

    public static Path writeTo(Path root) throws IOException {
        Path dir = root.resolve("case-pack");
        Path base = dir.resolve("baseline");
        Path steps = dir.resolve("steps");
        Files.createDirectories(base);
        Files.createDirectories(steps);

        List<CaseData.Optic> optics = CaseData.gridStates(8); // 16 linear + 4 QWP states per side
        var cells = CaseData.synthesize(optics, 12.0, 1.0, 0.9, 20260924L, false);

        write(base, "01-optical-state-codes.json", CaseData.statesDoc("S0123", "POS_CCW", optics));
        write(base, "02-raw-intensities.json", CaseData.intensityDoc(cells));
        write(base, "03-dark-frame.json", CaseData.darkDoc(12.0, 0.25));
        write(base, "04-reference-sample.json", CaseData.referenceDoc(1.0, 0.004, "ref-v1"));
        write(base, "05-instrument-psg.json", CaseData.instrumentDoc("PSG"));
        write(base, "06-instrument-psa.json", CaseData.instrumentDoc("PSA"));
        write(base, "07-wavelength.json", CaseData.wavelengthDoc(632.8));
        write(base, "08-covariance.json", CaseData.covarianceDoc(cells, false, 0.0));

        // ---- boundary step inputs ----
        var singularOptics = CaseData.gridStates(8);
        var singularCells = CaseData.synthesize(singularOptics, 12.0, 1.0, 0.4, 7711L, true);
        Path sing = steps.resolve("singular-design");
        Files.createDirectories(sing);
        write(sing, "01-optical-state-codes.json", CaseData.statesDoc("S0123", "POS_CCW", singularOptics));
        write(sing, "02-raw-intensities.json", CaseData.intensityDoc(singularCells));
        write(sing, "03-dark-frame.json", CaseData.darkDoc(12.0, 0.16));
        write(sing, "04-reference-sample.json", CaseData.referenceDoc(1.0, 0.003, "ref-v1"));
        write(sing, "05-instrument-psg.json", CaseData.instrumentDoc("PSG"));
        write(sing, "06-instrument-psa.json", CaseData.instrumentDoc("PSA"));
        write(sing, "07-wavelength.json", CaseData.wavelengthDoc(632.8));
        write(sing, "08-covariance.json", CaseData.covarianceDoc(singularCells, false, 0));

        writeFile(steps.resolve("dark-revision.json"), CaseData.darkDoc(8.0, 0.10));
        writeFile(steps.resolve("stokes-order-change.json"), CaseData.statesDoc("S0S2S1S3", "POS_CCW", optics));
        writeFile(steps.resolve("rotation-sign-change.json"), CaseData.statesDoc("S0123", "POS_CW", optics));
        writeFile(steps.resolve("nonphysical-noise.json"), CaseData.injectNonphysical(CaseData.intensityDoc(cells), 4242L));
        writeFile(steps.resolve("reference-revision.json"), CaseData.referenceDoc(0.97, 0.012, "ref-v2"));
        writeFile(steps.resolve("covariance-correlated.json"), CaseData.covarianceDoc(cells, true, 0.35));

        Files.writeString(dir.resolve("manifest.json"), OM.writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest()));
        Files.writeString(dir.resolve("README.md"), readme());
        return dir;
    }

    static ObjectNode manifest() {
        ObjectNode m = OM.createObjectNode();
        m.put("title", "偏振矩阵室：Mueller 测量、仪器校正与物理可实现性审查");
        m.put("domain", "polarization-optics/mueller-matrix");
        m.put("ruleVersion", StoreEvents.RULE_VERSION);
        m.put("narrative", "偏振实验组在多组发生器/分析器状态下导入强度，估计 Mueller 矩阵并审查校正、分解与误差传播");
        ObjectNode baseline = m.putObject("baseline");
        String[] files = {"01-optical-state-codes.json", "02-raw-intensities.json", "03-dark-frame.json",
                "04-reference-sample.json", "05-instrument-psg.json", "06-instrument-psa.json",
                "07-wavelength.json", "08-covariance.json"};
        String[] kinds = {"state-code", "intensity", "dark", "reference", "instrument-psg",
                "instrument-psa", "wavelength", "covariance"};
        for (int i = 0; i < files.length; i++) {
            baseline.put(kinds[i], "baseline/" + files[i]);
        }
        ArrayNode steps = m.putArray("replaySteps");
        step(steps, "S1", "BASELINE_IMPORT", "initial separated import; rollback on any failure", "baseline/");
        step(steps, "S2", "SINGULAR_DESIGN", "rank-deficient design matrix; no forced inverse; family candidates", "steps/singular-design/");
        step(steps, "S3", "DARK_REVISION", "dark frame value 12->8; only dark-dependent results invalidated", "steps/dark-revision.json");
        step(steps, "S4", "STOKES_ORDER", "S0123 -> S0S2S1S3; convention enters fingerprint", "steps/stokes-order-change.json");
        step(steps, "S5", "ROTATION_SIGN", "POS_CCW -> POS_CW; convention enters fingerprint", "steps/rotation-sign-change.json");
        step(steps, "S6", "NONPHYSICAL_NOISE", "noisy unphysical fit; violations flagged, not projected", "steps/nonphysical-noise.json");
        step(steps, "S7", "REFERENCE_REVISION", "reference gain 1.00 v1 -> 0.97 v2; local invalidation", "steps/reference-revision.json");
        step(steps, "S8", "CORRELATED_ERRORS", "diagonal -> correlated covariance rho=0.35; error propagation changes", "steps/covariance-correlated.json");
        step(steps, "S9", "PARAM_REVISION", "condition threshold tightened (parameter revision)", null);
        step(steps, "S10", "LOCAL_SOURCE_CHANGE", "one intensity cell corrected; only dependent estimate changes", null);
        step(steps, "S11", "CONFLICTING_DECISIONS", "two windows accept vs reject the same candidate; conflict + diff", null);
        return m;
    }

    private static void step(ArrayNode steps, String id, String type, String expect, String path) {
        ObjectNode s = steps.addObject();
        s.put("id", id);
        s.put("type", type);
        s.put("expect", expect);
        if (path != null) {
            s.put("path", path);
        }
    }

    static String readme() {
        return """
                # 偏振矩阵室离线案例包
                ## Mueller 测量、仪器校正与物理可实现性审查

                叙事：偏振实验组在多组偏振发生器 (PSG) 与分析器 (PSA) 状态下导入探测器强度，
                先分离保存原始强度、光学状态编码、暗场、参考样、仪器矩阵、波长和协方差，
                再估计 Mueller 矩阵，审查校正、分解候选与误差传播。

                目录 baseline/ 是初始版本，七类来源分别成文件；steps/ 是边界情形输入：

                - singular-design/   设计矩阵奇异（重复行造成 1 维零空间），禁止求逆，保留候选族
                - dark-revision.json 暗场修订 12 -> 8 count
                - stokes-order-change.json  Stokes 顺序 S0123 -> S0S2S1S3
                - rotation-sign-change.json 旋转正负 POS_CCW -> POS_CW
                - nonphysical-noise.json    噪声导致非物理 Mueller 候选（只标记不投影）
                - reference-revision.json   参考样 v1 -> v2，增益 1.00 -> 0.97
                - covariance-correlated.json 独立噪声 -> 相关误差 rho=0.35

                所有领域量携带单位或坐标基准（count / nm->m / lab / detector / vacuum）。
                无法规范化的值留在原位置并附 diagnostic，派生层才做单位规范化。

                判定原则（也写在机器可读事件 fingerprint/ruleVersion 中）：
                1. 近奇异设计矩阵不强行求逆；
                2. 非物理 Mueller 候选标出违约，不投影后冒充原解；
                3. 旋转约定与 Stokes 顺序进入指纹。

                manifest.json 给出 S1..S11 的完整重放计划，包含一次参数修订、
                一次只影响局部依赖的源数据改动和一次互相冲突的人工决定。
                """;
    }

    private static void writeFile(Path file, ObjectNode doc) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file,
                OM.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
    }

    private static void write(Path dir, String name, ObjectNode doc) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name),
                OM.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
    }
}
