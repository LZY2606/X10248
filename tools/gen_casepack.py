#!/usr/bin/env python3
"""Domain case-pack generator for the Mueller polarimetry workspace."""
import json, math, os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "casepack")

def mmul(A, B):
    n, k, m = len(A), len(B), len(B[0])
    return [[sum(A[i][t] * B[t][j] for t in range(k)) for j in range(m)] for i in range(n)]
def mv(A, v): return [sum(A[i][j] * v[j] for j in range(4)) for i in range(4)]
def dot(a, b): return sum(x * y for x, y in zip(a, b))
def rot(theta):
    c, s = math.cos(2 * theta), math.sin(2 * theta)
    return [[1,0,0,0],[0,c,s,0],[0,-s,c,0],[0,0,0,1]]
def retarder(delta, theta):
    c, s = math.cos(delta), math.sin(delta)
    M0 = [[1,0,0,0],[0,1,0,0],[0,0,c,s],[0,0,-s,c]]
    return mmul(mmul(rot(-theta), M0), rot(theta))
def transpose(A): return [[A[j][i] for j in range(4)] for i in range(4)]
def qwp(phi): return retarder(math.pi / 2, phi)
def kron(a, b): return [a[i] * b[j] for i in range(4) for j in range(4)]

def jacobi_eig(A):
    n = len(A)
    A = [row[:] for row in A]
    for _ in range(300):
        off = sum(A[i][j]**2 for i in range(n) for j in range(n) if i != j)
        if off < 1e-24: break
        for p in range(n):
            for q in range(p+1, n):
                if abs(A[p][q]) < 1e-15: continue
                tau = (A[q][q]-A[p][p])/(2*A[p][q])
                t = (1 if tau>=0 else -1)/(abs(tau)+math.sqrt(1+tau*tau))
                c = 1/math.sqrt(1+t*t); s = t*c
                for i in range(n):
                    x, y = A[i][p], A[i][q]
                    A[i][p] = c*x - s*y; A[i][q] = s*x + c*y
                for i in range(n):
                    x, y = A[p][i], A[q][i]
                    A[p][i] = c*x - s*y; A[q][i] = s*x + c*y
    return sorted(A[i][i] for i in range(n))

def svd_rank_cond(W):
    n, k = len(W), 16
    WtW = [[sum(W[r][i]*W[r][j] for r in range(n)) for j in range(k)] for i in range(k)]
    eig = jacobi_eig(WtW)
    eig = [max(e, 0.0) for e in eig]
    rank = sum(1 for e in eig if e > 1e-10 * max(eig))
    sv = sorted(math.sqrt(e) for e in eig)
    cond = sv[-1] / sv[16-rank] if rank else float("inf")
    return rank, cond

I2 = [[1,0],[0,1]]; Z2 = [[0,0],[0,0]]
SX = [[0,1],[1,0]]
SY_IM = [[0,-1],[1,0]]
SZ = [[1,0],[0,-1]]
SIG = [(I2,Z2),(SX,Z2),(Z2,SY_IM),(SZ,Z2)]

def coherency_eigs(M):
    Hre = [[0.0]*4 for _ in range(4)]; Him = [[0.0]*4 for _ in range(4)]
    for i in range(4):
        for j in range(4):
            c = M[i][j]/4.0
            Are, Aim = SIG[i]; Bre, Bim = SIG[j]
            for a in range(2):
                for b in range(2):
                    for p in range(2):
                        for q in range(2):
                            r, t = a*2+p, b*2+q
                            Hre[r][t] += c*(Are[a][b]*Bre[p][q] + Aim[a][b]*Bim[p][q])
                            Him[r][t] += c*(-Are[a][b]*Bim[p][q] + Aim[a][b]*Bre[p][q])
    N = 8
    A = [[0.0]*N for _ in range(N)]
    for i in range(4):
        for j in range(4):
            A[i][j] = Hre[i][j]; A[i][j+4] = -Him[i][j]
            A[i+4][j] = Him[i][j]; A[i+4][j+4] = Hre[i][j]
    return jacobi_eig(A)[::2]

Mret = retarder(1.4, 0.5)
Mdep = [[1,0,0,0],[0,0.35,0,0],[0,0,0.35,0],[0,0,0,0.35]]
Mtrue = [[0.8*Mret[i][j] + 0.2*Mdep[i][j] for j in range(4)] for i in range(4)]
Minst = [
 [1.0,  0.004, 0.0,    0.0],
 [0.003,0.99,  0.002,  0.0],
 [0.0, -0.002, 0.995,  0.003],
 [0.0,  0.0,  -0.003,  0.985]]
Mgen = mmul(Minst, Mtrue)

angles = [0, 30, 45, 60, 90, 135]
H = [1.0, 1.0, 0.0, 0.0]
states = []
for ga in angles:
    g = mv(qwp(math.radians(ga)), H)
    for aa in angles:
        a = mv(transpose(qwp(math.radians(aa))), H)
        states.append({"id": f"g{ga:03d}-a{aa:03d}", "generator": g, "analyzer": a})

GAIN = 4000.0
def dark_i(i): return 100.0 + 2.0*math.sin(0.9*i)
def noise_i(i): return 7.0*math.sin(1.7*i) + 3.0*math.cos(0.31*i)

intensity_v1 = [round(GAIN*dot(s["analyzer"], mv(Mgen, s["generator"])) + dark_i(i) + noise_i(i), 4)
                for i, s in enumerate(states)]
dark_v1 = [round(dark_i(i), 4) for i in range(36)]
cov_diag_v1 = [max(intensity_v1[i], 50.0) for i in range(36)]

W = [kron(s["analyzer"], s["generator"]) for s in states]
rank, cond = svd_rank_cond(W)
eigs_true = coherency_eigs(Mtrue)
print("v1 design rank/cond:", rank, f"{cond:.3g}", "min coherency eig(Mtrue):", f"{eigs_true[0]:.4f}")
assert rank == 16 and cond < 1e6 and eigs_true[0] > 0.01

def solve16(A, b):
    n = 16
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for col in range(n):
        piv = max(range(col, n), key=lambda r: abs(M[r][col]))
        M[col], M[piv] = M[piv], M[col]
        d = M[col][col]
        M[col] = [x / d for x in M[col]]
        for r in range(n):
            if r != col and abs(M[r][col]) > 0:
                f = M[r][col]
                M[r] = [x - f * y for x, y in zip(M[r], M[col])]
    return [M[i][n] for i in range(n)]

def ls_solution(intens):
    y = [intens[i] - dark_i(i) for i in range(36)]
    WtW = [[sum(W[r][i]*W[r][j] for r in range(36)) for j in range(16)] for i in range(16)]
    Wty = [sum(W[r][i]*y[r] for r in range(36)) for i in range(16)]
    return solve16(WtW, Wty)

def vec_to_m(v): return [[v[i*4+j] for j in range(4)] for i in range(4)]

# search a deterministic noise realization whose LS solution is non-physical
import random
rng = random.Random(20260919)
intensity_v4 = None
for trial in range(2000):
    extra = [rng.gauss(0.0, 100.0) for _ in range(36)]
    cand = [round(intensity_v1[i] + extra[i], 4) for i in range(36)]
    if min(cand) < 50: continue
    mls = ls_solution(cand)
    emin = coherency_eigs(vec_to_m(mls))[0] / mls[0]
    if emin < -0.004:
        intensity_v4 = cand
        print("nonphysical noise found at trial", trial, "min eig:", f"{emin:.4f}",
              "min intensity:", f"{min(cand):.1f}")
        break
assert intensity_v4 is not None, "no nonphysical noise realization found"

gH = [1.0, 1.0, 0.0, 0.0]; gV = [1.0, -1.0, 0.0, 0.0]   # linear polarizer H/V
aH = [1.0, 1.0, 0.0, 0.0]; aV = [1.0, -1.0, 0.0, 0.0]
states_sing, vals_sing = [], []
for i in range(36):
    gg = gH if (i // 2) % 2 == 0 else gV
    aa = aH if i % 2 == 0 else aV
    states_sing.append({"id": f"sing-{i:02d}", "generator": gg, "analyzer": aa})
    vals_sing.append(round(GAIN*dot(aa, mv(Mgen, gg)) + dark_i(i) + noise_i(i), 4))
Ws = [kron(s["analyzer"], s["generator"]) for s in states_sing]
ranks, conds = svd_rank_cond(Ws)
print("singular design rank/cond:", ranks, f"{conds:.3g}")
assert ranks < 16

P = [0, 3, 1, 2]  # canonical IQUV component j -> IVUQ slot P[j]
def reorder(v):
    out = [0.0]*4
    for j in range(4): out[P[j]] = v[j]
    return out
states_ivuq = [{"id": s["id"], "generator": reorder(s["generator"]),
                "analyzer": reorder(s["analyzer"])} for s in states]

rho = 0.3
cov_v2 = [[math.sqrt(cov_diag_v1[i]*cov_diag_v1[j]) * rho**abs(i-j)
           if abs(i-j) <= 3 else 0.0 for j in range(36)] for i in range(36)]
for i in range(36): cov_v2[i][i] = cov_diag_v1[i]
dark_v2 = [round(dark_i(i) + 1.5, 4) for i in range(36)]

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(obj, f, indent=2)

V1 = os.path.join(ROOT, "sources", "v1")
V2 = os.path.join(ROOT, "sources", "v2")
write(f"{V1}/raw_intensity.json", {"kind":"raw_intensity","units":"counts",
    "frame":"detector:pixel-integrated",
    "note":"rotating-QWP polarimeter, 36 states, HeNe 632.8nm, 0.8s exposure",
    "values":intensity_v1})
write(f"{V1}/state_encoding.json", {"kind":"state_encoding","units":"dimensionless",
    "frame":"stokes:IQUV;rotation:optical_ccw",
    "note":"generator=H polarizer + QWP(phi); analyzer=QWP(phi)^T + H polarizer",
    "states":states})
write(f"{V1}/dark_field.json", {"kind":"dark_field","units":"counts",
    "frame":"detector:pixel-integrated","note":"shutter closed, same exposure",
    "values":dark_v1})
write(f"{V1}/reference_sample.json", {"kind":"reference_sample","units":"dimensionless",
    "frame":"stokes:IQUV","note":"dry nitrogen air path",
    "matrix":[[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]})
write(f"{V1}/instrument_matrix.json", {"kind":"instrument_matrix","units":"dimensionless",
    "frame":"stokes:IQUV","note":"factory calibration card CAL-2026-04",
    "matrix":Minst})
write(f"{V1}/wavelength.json", {"kind":"wavelength","units":"nm",
    "frame":"vacuum","value":632.8,
    "note":"helium-neon line, calibrated wavemeter reading"})
write(f"{V1}/covariance.json", {"kind":"covariance","units":"counts^2",
    "frame":"detector:pixel-integrated","note":"Poisson-like diagonal covariance",
    "diagonal":cov_diag_v1})

write(f"{V2}/raw_intensity-singular.json", {"kind":"raw_intensity","units":"counts",
    "frame":"detector:pixel-integrated","note":"36 measurements but only H/V states (rank deficient design)",
    "values":vals_sing})
write(f"{V2}/raw_intensity-restored.json", {"kind":"raw_intensity","units":"counts",
    "frame":"detector:pixel-integrated","note":"re-export of the baseline 36-state intensities",
    "values":intensity_v1})
write(f"{V2}/raw_intensity-nonphysical.json", {"kind":"raw_intensity","units":"counts",
    "frame":"detector:pixel-integrated","note":"noisy reprocessing whose LS solution is non-physical",
    "values":intensity_v4})
write(f"{V2}/state_encoding-singular.json", {"kind":"state_encoding","units":"dimensionless",
    "frame":"stokes:IQUV;rotation:optical_ccw","note":"H/V only design (rank deficient)",
    "states":states_sing})
write(f"{V2}/state_encoding-restored.json", {"kind":"state_encoding","units":"dimensionless",
    "frame":"stokes:IQUV;rotation:optical_ccw","note":"re-export of the baseline 36-state design",
    "states":states})
write(f"{V2}/state_encoding-ivuq.json", {"kind":"state_encoding","units":"dimensionless",
    "frame":"stokes:IVUQ;rotation:optical_ccw","note":"same physical states serialized in IVUQ order",
    "states":states_ivuq})
write(f"{V2}/dark_field.json", {"kind":"dark_field","units":"counts",
    "frame":"detector:pixel-integrated","note":"revised dark: thermal drift correction +1.5 counts",
    "values":dark_v2})
write(f"{V2}/covariance-correlated.json", {"kind":"covariance","units":"counts^2",
    "frame":"detector:pixel-integrated","note":"correlated covariance, rho=0.3 lag<=3",
    "matrix":cov_v2})
write(f"{V2}/reference_sample.json", {"kind":"reference_sample","units":"dimensionless",
    "frame":"stokes:IQUV","note":"recalibrated air reference scan 2026-09",
    "matrix":[[0.997,0,0,0],[0,0.997,0,0],[0,0,0.997,0],[0,0,0,0.997]]})
print("casepack sources written")
