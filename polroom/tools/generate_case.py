#!/usr/bin/env python3
"""Generate the offline Mueller-lab case package: case/manifest.json + case/replay.jsonl.

Pure standard library.  The package is domain material, not a generic table:
raw intensities, optical-state codes, dark frames, reference samples, instrument
matrices, wavelength and covariance are each separate source versions, and the
replay walks through singular design, dark revision, Stokes ordering, rotation
sign, unphysical noise, reference revision and correlated errors.
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mat4

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "src", "main", "resources", "cases")


class Lcg:
    def __init__(self, seed=20260924):
        self.s = seed

    def next(self):
        self.s = (6364136223846793005 * self.s + 1442695040888963407) & ((1 << 64) - 1)
        return ((self.s >> 33) / float(1 << 31)) - 1.0


def noise_vec(n, sigma, seed):
    rng = Lcg(seed)
    return [rng.next() * sigma for _ in range(n)]


def build_true_matrix():
    ret = mat4.retarder(math.radians(30), math.radians(22), sign=1)
    dia = mat4.diattenuator(0.94, 0.88, math.radians(12), sign=1)
    base = mat4.mm(ret, dia)
    weak_depol = [[0.985 if i == 0 and j == 0 else
                   (0.97 if 1 <= i <= 3 and i == j else 0.0)
                   for j in range(4)] for i in range(4)]
    weak_depol[0][0] = 1.0
    return mat4.mm(weak_depol, base)


def state_set(kind, sign=1):
    if kind == "full":
        return [
            ("H", mat4.linear_stokes(0, sign)),
            ("V", mat4.linear_stokes(90, sign)),
            ("P", mat4.linear_stokes(45, sign)),
            ("R", [1.0, 0.0, 0.0, 1.0]),
        ]
    if kind == "linear_only":
        return [
            ("H", mat4.linear_stokes(0, sign)),
            ("V", mat4.linear_stokes(90, sign)),
            ("P", mat4.linear_stokes(45, sign)),
            ("M", mat4.linear_stokes(-45, sign)),
        ]
    if kind == "near":
        eps = 1.0e-3
        return [
            ("H", mat4.linear_stokes(0, sign)),
            ("V", mat4.linear_stokes(90, sign)),
            ("P", mat4.linear_stokes(45, sign)),
            ("H*", [1.0, 1.0 - eps * eps, eps, eps]),
        ]
    raise ValueError(kind)


def design_rows(psgs, psas):
    rows = []
    for _, pa in psas:
        for _, pg in psgs:
            rows.append([pa[i] * pg[j] for i in range(4) for j in range(4)])
    return rows


def measure(true_m, psgs, psas, dark, gain, noise, scale=4200.0):
    intensities = []
    rows = []
    labels = []
    for ai, (an, pa) in enumerate(psas):
        for gi, (gn, pg) in enumerate(psgs):
            k = ai * len(psgs) + gi
            ideal = sum(pa[i] * true_m[i][j] * pg[j] for i in range(4) for j in range(4))
            value = scale * gain[k] * ideal + dark[k] + noise[k]
            intensities.append(value)
            rows.append([pa[i] * pg[j] for i in range(4) for j in range(4)])
            labels.append(f"PSA={an},PSG={gn}")
    return intensities, rows, labels


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")


def correlated_cov(n, variance, rho):
    return [[(1.0 if i == j else rho) * math.sqrt(variance[i] * variance[j])
             for j in range(n)] for i in range(n)]


def main():
    true_m = build_true_matrix()
    psgs = state_set("full")
    psas = state_set("full")
    n = 16
    dark = [42.0 + 0.7 * i for i in range(n)]
    gain = [1.0 + 0.004 * ((i * 7) % 5 - 2) for i in range(n)]
    noise = noise_vec(n, 7.5, seed=11)
    intensities, _, labels = measure(true_m, psgs, psas, dark, gain, noise)
    variance = [(3.2 + 0.05 * i) ** 2 for i in range(n)]
    cov = [[(1.0 if i == j else 0.0) * math.sqrt(variance[i] * variance[j])
            for j in range(n)] for i in range(n)]

    instrument = mat4.eye(4)
    wavelength = {"value": 532.0, "unit": "nm", "coordinateBasis": "vacuum wavelength"}

    raw_text = "\n".join(["# raw detector counts, PSA row then PSG column, scale=4200"]
                         + [f"{labels[i]}\t{intensities[i]:.6f}\tcount" for i in range(n)])

    states = []
    for role, group in (("PSG", psgs), ("PSA", psas)):
        for name, vec in group:
            code = {"H": "H", "V": "V", "P": "P", "M": "M"}.get(name, "R" if name == "R" else "E")
            states.append({
                "role": role,
                "index": len([s for s in states if s["role"] == role]),
                "code": code,
                "angleDeg": 0.0 if name in ("H", "R") else
                            (90.0 if name == "V" else 45.0 if name == "P" else -45.0),
                "ellipticityDeg": 0.0,
                "stokesOrder": "SHV",
                "rotationConvention": "RIGHT_HANDED",
                "stokes": vec,
                "unit": "normalized Stokes (dimensionless)",
                "coordinateBasis": "lab x=H, y=+45, propagation +z, RH convention",
            })

    baseline = {
        "package": "polroom-case-mueller-lab",
        "title": "偏振矩阵室：Mueller 测量、仪器校正与物理可实现性审查",
        "ruleVersion": "mueller-psvd/1.0",
        "svdTolerance": 1e-10,
        "nearSingularCondition": 1e6,
        "trueMatrixForAssertion": true_m,
        "measurementOrder": labels,
        "sources": {
            "raw_intensity": {"rawRepr": raw_text, "unit": "count",
                              "coordinateBasis": "detector counts, pre-correction",
                              "values": intensities},
            "optical_state": {"unit": "normalized Stokes (dimensionless)",
                              "coordinateBasis": "lab x=H,y=+45,+z",
                              "states": states},
            "dark_frame": {"unit": "count", "coordinateBasis": "same exposure/state order",
                           "values": dark},
            "reference_sample": {"unit": "dimensionless gain",
                                 "coordinateBasis": "air reference, per state",
                                 "values": gain},
            "instrument_matrix": {"unit": "dimensionless",
                                  "coordinateBasis": "canonical SHV, lab frame",
                                  "matrix": instrument},
            "wavelength": wavelength,
            "covariance": {"unit": "count^2", "coordinateBasis": "state ordering as measurements",
                           "matrix": cov, "correlationRho": 0.0},
        },
    }

    # singular linear-only design
    lin_psgs = state_set("linear_only")
    lin_psas = state_set("linear_only")
    sing_rows = design_rows(lin_psgs, lin_psas)
    sing_s, sing_v = mat4.svd4_rows(sing_rows)
    sing_null = [sing_v[r][15] for r in range(16)]
    lin_dark = [40.0] * 16
    lin_gain = [1.0] * 16
    lin_noise = noise_vec(16, 4.0, seed=23)
    lin_int, _, lin_labels = measure(true_m, lin_psgs, lin_psas, lin_dark, lin_gain, lin_noise)

    # near-singular with one elliptically near-linear state
    near_psgs = state_set("near")
    near_psas = state_set("near")
    near_rows = design_rows(near_psgs, near_psas)
    near_s, near_v = mat4.svd4_rows(near_rows)
    near_dark = [41.0] * 16
    near_gain = [1.0] * 16
    near_noise = noise_vec(16, 3.0, seed=29)
    near_int, _, near_labels = measure(true_m, near_psgs, near_psas, near_dark, near_gain,
                                       near_noise)

    unphys = [[v for v in row] for row in true_m]
    for i in range(4):
        for j in range(4):
            unphys[i][j] *= 1.08
    unphys[0][0] = 0.6
    unphys[2][1] += 1.3

    dark_revised = [v + 6.0 for v in dark]
    gain_revised = [v * (1.0 + 0.012 * math.sin(i + 1)) for i, v in enumerate(gain)]
    cov_corr = correlated_cov(n, variance, 0.18)

    manifest = dict(baseline)
    manifest["scenarios"] = {
        "singular": {
            "states_psg": [{"code": c[0]} for c in lin_psgs],
            "states_psa": [{"code": c[0]} for c in lin_psas],
            "raw": lin_int,
            "dark": lin_dark,
            "singularValues": sing_s,
            "nullVector": sing_null,
            "labels": lin_labels,
            "expectedRank": 9,
            "expectedStatus": "RANK_DEFICIENT",
        },
        "near_singular": {
            "states_psg": [{"code": c[0]} for c in near_psgs],
            "states_psa": [{"code": c[0]} for c in near_psas],
            "raw": near_int,
            "dark": near_dark,
            "singularValues": near_s,
            "labels": near_labels,
            "expectedStatus": "NEAR_SINGULAR",
            "expectedMinSingularBelow": 1e-4,
        },
        "dark_revised": {"values": dark_revised, "unit": "count"},
        "stokes_siv": {"order": "SIV", "labels": ["I", "U", "V", "Q"]},
        "rotation_left": {"convention": "LEFT_HANDED"},
        "unphysical_candidate": {"matrix": unphys},
        "reference_revised": {"values": gain_revised, "unit": "dimensionless gain"},
        "covariance_correlated": {"matrix": cov_corr, "correlationRho": 0.18, "unit": "count^2"},
    }
    write(os.path.join(OUT, "manifest.json"), manifest)

    # replay script
    steps = [
        {"seq": 1, "op": "IMPORT_BASELINE", "branch": "main",
         "assert": {"sourceKinds": 7, "fingerprintStable": True,
                    "baselineStatus": "FULL_RANK"}},
        {"seq": 2, "op": "ADD_SCENARIO_SOURCES", "scenario": "singular", "branch": "rank-deficient",
         "forkFrom": "main",
         "assert": {"status": "RANK_DEFICIENT", "rank": 9, "noForcedInverse": True,
                    "candidateFamily": True, "allFeasibleKept": True}},
        {"seq": 3, "op": "ADD_SCENARIO_SOURCES", "scenario": "near_singular", "branch": "main",
         "assert": {"status": "NEAR_SINGULAR", "usesTruncatedSvd": True, "conditionLarge": True}},
        {"seq": 4, "op": "REVISE_SOURCE", "kind": "dark_frame", "scenario": "dark_revised",
         "branch": "main",
         "assert": {"newVersion": 2, "invalidatesDerivations": True,
                    "invalidationsScoped": True, "rawIntensityUntouched": True}},
        {"seq": 5, "op": "REVISE_CONVENTION", "scenario": "stokes_siv", "branch": "convention-siv",
         "forkFrom": "main",
         "assert": {"fingerprintChanges": True, "fingerprintContainsOrder": "SIV",
                    "oldBranchReadsShv": True}},
        {"seq": 6, "op": "REVISE_CONVENTION", "scenario": "rotation_left",
         "branch": "convention-left", "forkFrom": "main",
         "assert": {"fingerprintChanges": True, "fingerprintContainsRotation": "LEFT_HANDED",
                    "matrixOffDiagonalSignChange": True}},
        {"seq": 7, "op": "CANDIDATE_REVIEW", "scenario": "unphysical_candidate", "branch": "main",
         "assert": {"physicalRealizable": False, "violationsListed": True,
                    "notProjected": True, "rawCandidatePreserved": True}},
        {"seq": 8, "op": "REVISE_SOURCE", "kind": "reference_sample",
         "scenario": "reference_revised", "branch": "main",
         "assert": {"newVersion": 2, "invalidationsScoped": True,
                    "darkAndIntactSourcesStillValid": True}},
        {"seq": 9, "op": "REVISE_SOURCE", "kind": "covariance", "scenario": "covariance_correlated",
         "branch": "main",
         "assert": {"correlatedErrorsUsed": True, "standardErrorsChange": True,
                    "fingerprintChanges": True}},
        {"seq": 10, "op": "CONFLICTING_DECISIONS",
         "branchA": {"name": "accept-window", "verdict": "ACCEPTED",
                     "candidate": "min-norm"},
         "branchB": {"name": "reject-window", "verdict": "REJECTED",
                     "candidate": "min-norm"},
         "assert": {"laterCommitGetsConflict": True, "conflictShowsBothDiffs": True,
                    "undoRestoresPriorBranch": True}},
    ]
    with open(os.path.join(OUT, "replay.jsonl"), "w", encoding="utf-8") as f:
        for step in steps:
            f.write(json.dumps(step, ensure_ascii=False, sort_keys=True) + "\n")
    print("wrote", os.path.join(OUT, "manifest.json"), "and replay.jsonl")


if __name__ == "__main__":
    main()
