#!/usr/bin/env python3
"""
Offline verification script mirroring the Android Kotlin BoardCalibrator + ScreenAnalyzer HSV pipeline.
Usage:
    python3 tools/verify_cv_and_strategy.py path/to/screenshot.png
"""
import sys
import numpy as np
from PIL import Image

TILES_UV = [
    (0, 0), (0, 1), (0, 2), (0, 3), (0, 4), (0, 5), (1, 5), (2, 5),
    (3, 5), (3, 6), (3, 7), (4, 7), (5, 7), (6, 7), (7, 7), (7, 6),
    (7, 5), (7, 4), (7, 3), (6, 3), (5, 3), (5, 2), (5, 1), (5, 0),
    (4, 0), (3, 0), (2, 0), (1, 0),
]
SPECIAL = {0: 'GO', 4: 'CHANCE', 7: 'SWORDS', 11: 'WALLET', 14: 'UFO', 18: 'BAG', 21: 'PINK_WHEEL', 24: 'CHANCE'}
PILLS = {
    'O': (0.030, 0.270, 0.196, 0.298),
    'G': (0.804, 0.270, 0.970, 0.298),
    'B': (0.030, 0.670, 0.196, 0.697),
    'R': (0.804, 0.670, 0.970, 0.697),
}


def rgb_to_hsv(rgb):
    arr = rgb.astype(np.float32) / 255.0
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    df = mx - mn
    h = np.zeros_like(mx)
    mask = df > 1e-5
    rm = mask & (mx == r)
    gm = mask & (mx == g)
    bm = mask & (mx == b)
    h[rm] = (60 * ((g[rm] - b[rm]) / df[rm]) + 360) % 360
    h[gm] = (60 * ((b[gm] - r[gm]) / df[gm]) + 120) % 360
    h[bm] = (60 * ((r[bm] - g[bm]) / df[bm]) + 240) % 360
    s = np.where(mx > 1e-5, df / mx, 0.0)
    return h, s, mx


def auto_calibrate(im):
    H, W, _ = im.shape
    cx0, cx1 = int(0.42 * W), int(0.58 * W)
    uy0, uy1 = int(0.18 * H), int(0.36 * H)
    uh, us, uv = rgb_to_hsv(im[uy0:uy1, cx0:cx1])
    uys, uxs = np.where((uh >= 195) & (uh <= 222) & (us > 0.45) & (uv > 0.70))
    ufo_x = cx0 + uxs.mean()
    ufo_top_y = uy0 + uys.min() + (uys.max() - uys.min()) * 0.42

    gy0, gy1 = int(0.54 * H), int(0.72 * H)
    gh, gs, gv = rgb_to_hsv(im[gy0:gy1, cx0:cx1])
    gys, gxs = np.where((gh >= 95) & (gh <= 138) & (gs > 0.35) & (gv > 0.55))
    go_x = cx0 + gxs.mean()
    go_top_y = gy0 + gys.min() + (gys.max() - gys.min()) * 0.36

    x0 = (go_x + ufo_x) / 2.0
    dy = (go_top_y - ufo_top_y) / 14.0
    dx = dy * 1.402
    return x0, go_top_y, dx, dy


def count_vivid(patch):
    h, s, v = rgb_to_hsv(patch)
    return dict(
        O=int(((h >= 16) & (h <= 38) & (s > 0.62) & (v > 0.82)).sum()),
        G=int(((h >= 135) & (h <= 178) & (s > 0.48) & (v > 0.35)).sum()),
        B=int(((h >= 198) & (h <= 232) & (s > 0.58) & (v > 0.55)).sum()),
        R=int((((h >= 345) | (h <= 10)) & (s > 0.64) & (v > 0.55)).sum()),
        Y=int(((h >= 38) & (h <= 58) & (s > 0.45) & (v > 0.76)).sum()),
        MUD=int(((h >= 18) & (h <= 36) & (s > 0.45) & (v >= 0.32) & (v <= 0.78)).sum()),
    )


def analyze_screenshot(path):
    im = np.array(Image.open(path).convert("RGB"))
    H, W, _ = im.shape
    x0, y0, dx, dy = auto_calibrate(im)
    scale = dx / 87.2

    def S(px):
        return int(round(px * scale))

    bankrupt = set()
    for c, (nx0, ny0, nx1, ny1) in PILLS.items():
        p = im[int(ny0 * H):int(ny1 * H), int(nx0 * W):int(nx1 * W)]
        if ((p[:, :, 0] > 215) & (p[:, :, 1] > 215) & (p[:, :, 2] > 215)).mean() > 0.35:
            bankrupt.add(c)

    pawns = {}
    for c in ['O', 'G', 'B', 'R']:
        if c in bankrupt:
            pawns[c] = None
            continue
        best_sc, best_tile = 0, None
        for i, (u, v) in enumerate(TILES_UV):
            cx, cy = int(x0 + dx * (v - u)), int(y0 - dy * (u + v))
            hat = count_vivid(im[cy - S(110):cy - S(66), cx - S(28):cx + S(28)])
            face = count_vivid(im[cy - S(74):cy - S(42), cx - S(24):cx + S(24)])
            body = count_vivid(im[cy - S(46):cy - S(6), cx - S(32):cx + S(32)])
            if hat[c] >= S(160) and face['Y'] >= S(65) and body[c] >= S(130):
                sc = hat[c] * 1.5 + body[c] * 1.0 + min(face['Y'], 400) * 1.5
                if sc > best_sc:
                    best_sc, best_tile = sc, i
        pawns[c] = best_tile

    pawn_tiles = {t: c for c, t in pawns.items() if t is not None}
    houses = {}
    mud = []
    for i, (u, v) in enumerate(TILES_UV):
        if i in SPECIAL:
            continue
        cx, cy = int(x0 + dx * (v - u)), int(y0 - dy * (u + v))
        if i in pawn_tiles:
            wings = [
                im[cy - S(8):cy + S(14), cx - S(48):cx - S(24)],
                im[cy - S(8):cy + S(14), cx + S(24):cx + S(48)],
            ]
            ws = {'O': 0, 'G': 0, 'B': 0, 'R': 0}
            for w_patch in wings:
                h, s, val = rgb_to_hsv(w_patch)
                pad = (s >= 0.16) & (s <= 0.56) & (val >= 0.80)
                ws['O'] += int((pad & (h >= 12) & (h <= 38)).sum())
                ws['G'] += int((pad & (h >= 135) & (h <= 185)).sum())
                ws['B'] += int((pad & (h >= 195) & (h <= 238)).sum())
                ws['R'] += int((pad & ((h >= 330) | (h <= 12))).sum())
            best_c = max(ws, key=ws.get)
            houses[i] = best_c if ws[best_c] >= int(110 * scale * scale) else None
        else:
            roof = count_vivid(im[cy - S(46):cy - S(6), cx - S(28):cx + S(28)])
            if roof['MUD'] >= int(500 * scale * scale) and roof['O'] < int(250 * scale * scale):
                mud.append(i)
                houses[i] = None
                continue
            oh, os, ov = rgb_to_hsv(im[cy - S(22):cy + S(6), cx - S(34):cx + S(34)])
            pad = (os >= 0.16) & (os <= 0.65) & (ov >= 0.74)
            oval = {
                'O': int((pad & (oh >= 12) & (oh <= 38)).sum()),
                'G': int((pad & (oh >= 138) & (oh <= 182)).sum()),
                'B': int((pad & (oh >= 195) & (oh <= 238)).sum()),
                'R': int((pad & ((oh >= 335) | (oh <= 10))).sum()),
            }
            narrow = count_vivid(im[cy - S(64):cy - S(12), cx - S(20):cx + S(20)])
            total = {c: narrow[c] + roof[c] * 0.5 + oval[c] * 0.8 for c in ['O', 'G', 'B', 'R']}
            best_c = max(total, key=total.get)
            if (narrow[best_c] >= 200 * scale * scale and total[best_c] >= 280 * scale * scale) or (
                oval[best_c] >= 75 * scale * scale and total[best_c] >= 240 * scale * scale
            ):
                houses[i] = best_c
            else:
                houses[i] = None

    print(f"File: {path} ({W}x{H})")
    print(f"  Calibration: x0={x0:.1f}, y0={y0:.1f}, dx={dx:.1f}, dy={dy:.1f}")
    print(f"  Bankrupt: {sorted(bankrupt)}")
    print(f"  Pawns:    {pawns}")
    print(f"  Mud:      {mud}")
    print(f"  Houses:   { {k: v for k, v in sorted(houses.items()) if v is not None} }")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        analyze_screenshot(arg)
