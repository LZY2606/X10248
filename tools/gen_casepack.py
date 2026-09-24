#!/usr/bin/env python3
"""Generate the offline polarimetry case pack (domain-specific, not a generic table).

Outputs src/main/resources/casepack/polarization-chamber-case.json with:
  - baseline sources stored separately: intensities, optics, dark, reference,
    instrument matrix, wavelength, covariance
  - raw representations + units preserved (normalization only in derived layer)
  - a replay plan exercising: singular design, dark revision, Stokes-order change,
    rotation sign, non-physical noisy solution, reference revision, correlated errors
"""
import json
import math
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                   "casepack", "polarization-chamber-case.json")

# ---- Physics helpers ------------------------------------------------------
def lin_pol_state(angle_deg):
    a = math.radians(angle_deg)
    return [1.0, math.cos(2*a), math.sin(2*a), 0.0]

def retarder_m(delta_deg, axis_deg):
    d = math.radians(delta_deg)
    t = math.radians(axis_deg)
    c, s = math.cos(d/2), math.sin(d/2)
    # linear retarder Mueller matrix
    c2, s2 = math.cos(2*t), math.sin(2*t)
    return [
        [1, 0, 0, 0],
        [0, c2*c2 + s2*s2*math.cos(d), c2*s2*(1-math.cos(d)), s2*math.sin(d)],
        [0, c2*s2*(1-math.cos(d)), s2*s2 + c2*c2*math.cos(d), -c2*math.sin(d)],
        [0, -s2*math.sin(d), c2*math.sin(d), math.cos(d)],
    ]

def diattenuator_m(d, axis_deg):
    t = math.radians(axis_deg)
    ad = math.sqrt(1-d*d)
    c2, s2 = math.cos(2*t), math.sin(2*t)
    return [
        [1, d*c2, d*s2, 0],
        [d*c2, c2*c2 + ad*s2*s2, (1-ad)*c2*s2, 0],
        [d*s2, (1-ad)*c2*s2, s2*s2 + ad*c2*c2, 0],
        [0, 0, 0, ad],
    ]

def mmul(a, b):
    return [[sum(a[i][k]*b[k][j] for k in range(4)) for j in range(4)]
            for i in range(4)]

def kron_row(an, g):
    out = [0.0]*16
    for row in range(4):
        for col in range(4):
            out[col*4+row] = an[row]*g[col]
    return out

# ---- Ground-truth sample: weak diattenuator then quarter-wave retarder -----
md = diattenuator_m(0.12, 25.0)
mr = retarder_m(90.0, 40.0)
M_TRUE = mmul(mr, md)
print("m00 =", M_TRUE[0][0])

# four canonical PSG / PSA states: 0/45/90 linear + RCP
ANGLES = [0.0, 45.0, 90.0]
def states():
    res = [("H", lin_pol_state(0.0)), ("P", lin_pol_state(45.0)),
           ("V", lin_pol_state(90.0)), ("R", [1.0, 0.0, 0.0, 1.0])]
    return res

PSG = states()
PSA = states()

GAIN = 0.83
DARK_NOMINAL = 120.0
EXPOSURE_S = 0.05

def predict_counts(psg_s, psa_s, dark=DARK_NOMINAL, gain=GAIN, m=M_TRUE):
    vecm = [0.0]*16
    for col in range(4):
        for row in range(4):
            vecm[col*4+row] = m[row][col]
    row = kron_row(psa_s, psg_s)
    flux = sum(row[k]*vecm[k] for k in range(16))
    # 10000 counts/sec scale for unit flux, exposure 0.05 s
    rate = 10000.0 * gain * flux
    return dark + rate * EXPOSURE_S

baseline_readings = []
for gid, gs in PSG:
    for aid, as_ in PSA:
        c = predict_counts(gs, as_)
        baseline_readings.append({
            "psgId": gid, "psaId": aid,
            "countsRaw": f"{c:.3f} counts",
            "exposureRaw": "50 ms",
            "note": "",
        })

# one physically saturated / unconvertible reading kept at source with diagnostic
baseline_readings.append({
    "psgId": "R", "psaId": "R",
    "countsRaw": "OVF counts",
    "exposureRaw": "50 ms",
    "note": "detector overflow; retained at source, never dropped for plotting",
})

print("baseline rows:", len(baseline_readings))

# ---- Instrument matrix W (16x16) for the canonical state set ----------------
def instrument_matrix():
    w = []
    for _, as_ in PSA:
        for _, gs in PSG:
            w.append(kron_row(as_, gs))
    return w

W = instrument_matrix()

def cov_matrix(correlated, sigma=4.0):
    n = 16
    c = [[0.0]*n for _ in range(n)]
    for i in range(n):
        c[i][i] = sigma*sigma
    if correlated:
        # shared source drift couples adjacent PSA channels
        for i in range(n):
            for j in range(n):
                if i != j:
                    c[i][j] = 0.35*sigma*sigma*math.exp(-abs(i-j)/3.0)
    return c

# ---- Variant bundles -------------------------------------------------------
def optics(stokes_order="S0,S1,S2,S3", rotation_sign="+1"):
    def enc(s, angle_deg=None):
        canon = list(s)
        labels = stokes_order.split(",")
        ordered = [0.0]*4
        for i in range(4):
            ordered[labels.index(f"S{i}")] = canon[i]
        if angle_deg is not None and rotation_sign == "-1" and 3 in [labels.index("S3")]:
            pass
        return ordered
    def reorder(s):
        canon = list(s)
        labels = stokes_order.split(",")
        ordered = [0.0]*4
        for i in range(4):
            ordered[labels.index(f"S{i}")] = canon[i]
        return ordered
    def apply_sign(s, angle_deg):
        out = list(s)
        if rotation_sign == "-1" and angle_deg is not None:
            out[2] = -out[2]
        return out
    psg = []
    for name, st in PSG:
        ang = {"H":0.0,"P":45.0,"V":90.0,"R":None}[name]
        s2 = apply_sign(st, ang)
        psg.append({"id": name, "stokes": reorder(s2), "angleDeg":
                    (f"{ang} deg" if ang is not None else None)})
    psa = []
    for name, st in PSA:
        ang = {"H":0.0,"P":45.0,"V":90.0,"R":None}[name]
        s2 = apply_sign(st, ang)
        psa.append({"id": name, "stokes": reorder(s2), "angleDeg":
                    (f"{ang} deg" if ang is not None else None)})
    return {
        "version": "optics-v1" if (stokes_order=="S0,S1,S2,S3" and rotation_sign=="+1")
                   else f"optics-{stokes_order.replace(',','')}-{rotation_sign}",
        "stokesOrder": stokes_order,
        "rotationSign": rotation_sign,
        "angleBasis": "lab-optical-axis; azimuth measured in plane facing detector",
        "psg": psg, "psa": psa,
    }

dark_baseline = {
    "version": "dark-v1",
    "note": "shutter-closed dark, per-PSA offset; exposure 50 ms",
    "readings": [{"psaId": aid, "countsRaw": f"{DARK_NOMINAL:.1f} counts"}
                 for aid, _ in PSA],
}
dark_revised = {
    "version": "dark-v2",
    "note": "revised dark subtraction after thermal drift review; PSA H channel +18 counts",
    "readings": [{"psaId": aid,
                  "countsRaw": f"{(DARK_NOMINAL + (18.0 if aid=='H' else 0.0)):.1f} counts"}
                 for aid, _ in PSA],
}

reference_v1 = {
    "version": "reference-v1",
    "name": "calibrated air-path reference",
    "m00Transmittance": GAIN,
    "basis": "measured at same wavelength/exposure; identity Mueller assumed",
    "mueller": [[1 if i==j else 0 for j in range(4)] for i in range(4)],
    "note": "gain 0.83",
}
reference_v2 = {
    **reference_v1,
    "version": "reference-v2",
    "m00Transmittance": 0.87,
    "note": "gain corrected 0.83 -> 0.87 after lamp power audit",
}

wavelength = {"quantityRaw": "532 nm", "basis": "vacuum wavelength",
              "note": "frequency-doubled Nd:YAG"}

cov_v1 = {"version": "covariance-v1", "correlated": False,
          "matrix": cov_matrix(False), "unit": "count^2",
          "note": "independent detector channels, sigma=4 counts"}
cov_v2 = {"version": "covariance-v2", "correlated": True,
          "matrix": cov_matrix(True), "unit": "count^2",
          "note": "correlated source drift added; off-diagonals non-zero"}

intensity_baseline = {"version": "intensity-v1", "unit": "counts",
                      "readings": baseline_readings}

# ---- Singular-design intensity: only H and V generator/analyzer states ------
# Rank of kron rows built from {H,V} only is 2*2=4 < 16 -> rank deficient.
singular_readings = []
for gid in ("H", "V"):
    gs = dict(PSG)[gid]
    for aid in ("H", "V"):
        as_ = dict(PSA)[aid]
        c = predict_counts(gs, as_)
        singular_readings.append({
            "psgId": gid, "psaId": aid,
            "countsRaw": f"{c:.3f} counts",
            "exposureRaw": "50 ms",
            "note": "subset experiment: only H/V states were scheduled",
        })
# repeat rows to 16 measurements but stay in the same 4-dimensional subspace
pairs = [("H","H"),("H","V"),("V","H"),("V","V")]
k = 0
while len(singular_readings) < 16:
    gid, aid = pairs[k % 4]
    c = predict_counts(dict(PSG)[gid], dict(PSA)[aid])
    singular_readings.append({
        "psgId": gid, "psaId": aid,
        "countsRaw": f"{c + 0.11*(k%3):.3f} counts",
        "exposureRaw": "50 ms",
        "note": "duplicate-state repeat; does not add design rank",
    })
    k += 1
intensity_singular = {"version": "intensity-singular-v1", "unit": "counts",
                      "readings": singular_readings}

# ---- Non-physical noisy solution: huge noise pushes estimate off the PSD cone
import random
random.seed(532)
noisy_readings = []
for gid, gs in PSG:
    for aid, as_ in PSA:
        c = predict_counts(gs, as_)
        c += random.gauss(0.0, 320.0)  # extreme noise: non-physical candidate expected
        noisy_readings.append({
            "psgId": gid, "psaId": aid,
            "countsRaw": f"{c:.3f} counts",
            "exposureRaw": "50 ms",
            "note": "high-noise batch (sigma 320 counts); candidate expected to violate PSD",
        })
intensity_noisy = {"version": "intensity-noisy-v1", "unit": "counts",
                   "readings": noisy_readings}

# ---- Local-dependency source change: only wavelength note changes, ----------
# demonstrating invalidation scope stays local (no derived object depends on it
# beyond labeling; estimates must not be recomputed).
wavelength_shift = {"quantityRaw": "532.5 nm", "basis": "vacuum wavelength",
                    "note": "refined wavemeter reading; labeling-only dependency"}

case = {
    "schemaVersion": 1,
    "caseId": "POL-CHAMBER-001",
    "label": "偏振矩阵室：Mueller 测量、仪器校正与物理可实现性审查",
    "baseline": {
        "intensity": intensity_baseline,
        "optics": optics(),
        "dark": dark_baseline,
        "reference": reference_v1,
        "instrument": {"version": "instrument-v1", "w": W,
                       "stokesOrder": "S0,S1,S2,S3", "rotationSign": "+1",
                       "basis": "kron(PSA,PSG) coefficient map, counts basis",
                       "note": "16x16 generator/analyzer instrument matrix"},
        "wavelength": wavelength,
        "covariance": cov_v1,
    },
    "replay": [
        {"seq": 1, "kind": "IMPORT_BASELINE", "branch": "main",
         "title": "Baseline import: seven source kinds stored separately",
         "sources": "baseline",
         "expect": {"status": "UNIQUE", "rank": 16}},
        {"seq": 2, "kind": "REVISE_DARK", "branch": "main",
         "title": "Dark revision dark-v1 -> dark-v2 (local dependent recompute)",
         "patch": {"dark": dark_revised},
         "expect": {"invalidateScope": ["estimate", "physics", "decomposition"],
                    "rawIntensity": "unchanged"}},
        {"seq": 3, "kind": "REVISE_REFERENCE", "branch": "main",
         "title": "Reference sample revision reference-v1 -> reference-v2",
         "patch": {"reference": reference_v2},
         "expect": {"invalidateScope": ["estimate", "physics", "decomposition"]}},
        {"seq": 4, "kind": "REVISE_COVARIANCE", "branch": "main",
         "title": "Correlated-error covariance covariance-v1 -> covariance-v2",
         "patch": {"covariance": cov_v2},
         "expect": {"invalidateScope": ["estimate.covariance", "errorpropagation"],
                    "correlated": True}},
        {"seq": 5, "kind": "SINGULAR_DESIGN", "branch": "branch/singular-design",
         "title": "Near-singular design must not be force-inverted",
         "sources": "baseline",
         "patch": {"intensity": intensity_singular},
         "expect": {"status": "INDETERMINATE", "maxRank": 15,
                    "rule": "NEAR_SINGULAR_NO_INVERSE",
                    "candidates": "all legal null-space solutions retained"}},
        {"seq": 6, "kind": "STOKES_ORDER_CHANGE", "branch": "branch/stokes-order",
         "title": "Stokes order S0,S1,S2,S3 -> S0,S2,S3,S1 changes fingerprint",
         "sources": "baseline",
         "patch": {"optics": optics("S0,S2,S3,S1", "+1")},
         "expect": {"fingerprintChanges": True,
                    "rule": "STOKES_ORDER_IN_FINGERPRINT"}},
        {"seq": 7, "kind": "ROTATION_SIGN_CHANGE", "branch": "branch/rotation-sign",
         "title": "Rotation sign +1 -> -1 changes fingerprint and S3 signs",
         "sources": "baseline",
         "patch": {"optics": optics("S0,S1,S2,S3", "-1")},
         "expect": {"fingerprintChanges": True,
                    "rule": "ROTATION_CONVENTION_IN_FINGERPRINT"}},
        {"seq": 8, "kind": "NONPHYSICAL_NOISE", "branch": "branch/nonphysical",
         "title": "Non-physical noisy candidate flagged, never projected-and-claimed",
         "sources": "baseline",
         "patch": {"intensity": intensity_noisy, "covariance": cov_v2},
         "expect": {"rule": "FLAG_VIOLATION_NO_PROJECTION",
                    "violationCodesPresent": ["NEGATIVE_COHERENCY_EIGENVALUE"]}},
        {"seq": 9, "kind": "CONFLICTING_DECISIONS", "branch": "main",
         "title": "Two reviewers accept different decomposition candidates concurrently",
         "conflict": {"candidateA": "LC-FORWARD", "candidateB": "LC-REVERSE"},
         "expect": {"secondCommit": "VERSION_CONFLICT",
                    "diffVisible": True, "undoRestores": True}},
        {"seq": 10, "kind": "WAVELENGTH_REFINE", "branch": "main",
         "title": "Labeling-only wavelength refinement shows scoped invalidation",
         "patch": {"wavelength": wavelength_shift},
         "expect": {"invalidateScope": ["wavelength.label"],
                    "estimate": "unchanged"}},
    ],
}

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(case, f, indent=2, ensure_ascii=False)
print("wrote", OUT)
