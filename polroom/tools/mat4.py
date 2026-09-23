"""Tiny 4x4 / vector helpers for offline Mueller case generation (stdlib only)."""
import math


def mat(a):
    return [list(r) for r in a]


def eye(n):
    return [[1.0 if i == j else 0.0 for j in range(n)] for i in range(n)]


def mm(a, b):
    ar, ac = len(a), len(a[0])
    br, bc = len(b), len(b[0])
    assert ac == br
    return [[sum(a[i][k] * b[k][j] for k in range(ac)) for j in range(bc)] for i in range(ar)]


def mt(a):
    return [list(row) for row in zip(*a)]


def mv(a, x):
    return [sum(a[i][j] * x[j] for j in range(len(x))) for i in range(len(a))]


def scale(a, s):
    return [[v * s for v in row] for row in a]


def add(a, b):
    return [[a[i][j] + b[i][j] for j in range(len(a[0]))] for i in range(len(a))]


def sub(a, b):
    return [[a[i][j] - b[i][j] for j in range(len(a[0]))] for i in range(len(a))]


def rot_quat(t):
    c, s = math.cos(2 * t), math.sin(2 * t)
    return [[1, 0, 0, 0], [0, c, s, 0], [0, -s, c, 0], [0, 0, 0, 1]]


def retarder(delta, theta, sign=1):
    t = sign * theta
    r = rot_quat(t)
    cd, sd = math.cos(delta), math.sin(delta)
    fast = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, cd, sd], [0, 0, -sd, cd]]
    return mm(mm(mt(r), fast), r)


def diattenuator(tmax, tmin, theta, sign=1):
    t = sign * theta
    a = (tmax + tmin) / 2.0
    d = (tmax - tmin) / (tmax + tmin)
    c, s = math.cos(2 * t), math.sin(2 * t)
    sp = math.sqrt(1 - d * d)
    return [
        [a, a * d * c, a * d * s, 0],
        [a * d * c, a * (c * c + sp * s * s), a * c * s * (1 - sp), 0],
        [a * d * s, a * c * s * (1 - sp), a * (s * s + sp * c * c), 0],
        [0, 0, 0, a * sp],
    ]


def linear_stokes(deg, sign=1):
    t = math.radians(sign * deg)
    return [1.0, math.cos(2 * t), math.sin(2 * t), 0.0]


def ellip_stokes(az_deg, el_deg, sign=1):
    t = math.radians(sign * az_deg)
    c = math.cos(2 * math.radians(el_deg))
    return [1.0, c * math.cos(2 * t), c * math.sin(2 * t), math.sin(2 * math.radians(el_deg))]


def svd4_rows(w):
    """SVD of an n x 16 matrix via Jacobi eigen-decomposition of W^T W."""
    n = len(w)
    g = [[sum(w[k][i] * w[k][j] for k in range(n)) for j in range(16)] for i in range(16)]
    eigvals, eigvecs = jacobi_eigen(g)
    sing = [math.sqrt(max(0.0, ev)) for ev in eigvals]
    order = sorted(range(16), key=lambda i: -sing[i])
    sing = [sing[i] for i in order]
    v = [[eigvecs[r][order[c]] for c in range(16)] for r in range(16)]
    return sing, v


def jacobi_eigen(a, sweeps=80):
    n = len(a)
    a = [row[:] for row in a]
    v = eye(n)
    for _ in range(sweeps):
        off = math.sqrt(sum(a[i][j] ** 2 for i in range(n) for j in range(n) if i != j))
        if off < 1e-14:
            break
        for p in range(n):
            for q in range(p + 1, n):
                if abs(a[p][q]) < 1e-15:
                    continue
                tau = (a[q][q] - a[p][p]) / (2 * a[p][q])
                if tau >= 0:
                    t = 1.0 / (tau + math.sqrt(1 + tau * tau))
                else:
                    t = -1.0 / (-tau + math.sqrt(1 + tau * tau))
                c = 1 / math.sqrt(1 + t * t)
                s = t * c
                app, aqq, apq = a[p][p], a[q][q], a[p][q]
                a[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq
                a[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq
                a[p][q] = a[q][p] = 0.0
                for k in range(n):
                    if k in (p, q):
                        continue
                    akp, akq = a[k][p], a[k][q]
                    a[k][p] = a[p][k] = c * akp - s * akq
                    a[k][q] = a[q][k] = s * akp + c * akq
                for k in range(n):
                    vkp, vkq = v[k][p], v[k][q]
                    v[k][p] = c * vkp - s * vkq
                    v[k][q] = s * vkp + c * vkq
    return [a[i][i] for i in range(n)], v
