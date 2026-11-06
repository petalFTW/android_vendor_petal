#!/usr/bin/env python3
# Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0
# site hero bloom, stretched for a phone. needs rsvg-convert, numpy, pillow
# usage: make_wallpaper.py out.png
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

W, H = 1440, 3040
CANVAS = "#0A0B10"
PETAL = "M0,0C-46,-120 -128,-250 -96,-392C-76,-480 -28,-536 0,-566C28,-536 76,-480 96,-392C128,-250 46,-120 0,0Z"
LINE = "#CDBEFF"

# stops lifted straight from the site, keep them in step
RINGS = [
    ("r1", ("#6B4BE0", "#3A2AA0", "#2A1F7A"), (1, .72, 0), (.62,), 4.90, range(-84, 85, 14), .70),
    ("r2", ("#A274F2", "#5E43C8", "#4A36AE"), (1, .62, 0), (.60,), 3.55, range(-80, 81, 16), .70),
    ("r3", ("#FFD9EC", "#C39DF0", "#8A6FD0"), (1, .62, 0), (.55,), 2.30, range(-76, 77, 19), .50),
]


def stops(colors, opacities, offsets):
    return "".join(
        f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>'
        for c, a, o in zip(colors, opacities, offsets))


def svg():
    defs = [f'<path id="hp" d="{PETAL}"/>']
    # floor glow first, then the night it fades into
    defs.append('<radialGradient id="glow" cx=".5" cy=".86" r=".5">'
                '<stop offset="0" stop-color="#A074FF" stop-opacity=".42"/>'
                '<stop offset="1" stop-color="#A074FF" stop-opacity="0"/></radialGradient>')
    defs.append('<radialGradient id="night" cx=".5" cy="1" r=".82">'
                '<stop offset="0" stop-color="#2A1B66"/><stop offset=".6" stop-color="#130E30"/>'
                f'<stop offset="1" stop-color="{CANVAS}"/></radialGradient>')
    # clock and status bar live up top, don't let the tips fight them
    defs.append('<linearGradient id="fade" x1="0" y1="0" x2="0" y2="1">'
                f'<stop offset="0" stop-color="{CANVAS}" stop-opacity=".96"/>'
                f'<stop offset=".26" stop-color="{CANVAS}" stop-opacity=".62"/>'
                f'<stop offset=".48" stop-color="{CANVAS}" stop-opacity="0"/>'
                f'<stop offset=".66" stop-color="{CANVAS}" stop-opacity="0"/>'
                f'<stop offset=".76" stop-color="{CANVAS}" stop-opacity=".07"/>'
                f'<stop offset=".86" stop-color="{CANVAS}" stop-opacity=".24"/>'
                f'<stop offset=".94" stop-color="{CANVAS}" stop-opacity=".5"/>'
                f'<stop offset="1" stop-color="{CANVAS}" stop-opacity=".74"/></linearGradient>')
    body = [f'<rect width="{W}" height="{H}" fill="{CANVAS}"/>',
            f'<rect width="{W}" height="{H}" fill="url(#night)"/>',
            f'<rect width="{W}" height="{H}" fill="url(#glow)"/>']
    for name, colors, opacities, mid, scale, angles, alpha in RINGS:
        defs.append(f'<linearGradient id="{name}" x1=".5" y1="1" x2=".5" y2="0">'
                    + stops(colors, opacities, (0, mid[0], 1)) + '</linearGradient>')
        petals = "".join(
            f'<use href="#hp" transform="rotate({a}) scale({scale})" fill="url(#{name})" '
            f'fill-opacity="{alpha}" stroke="{LINE}" stroke-opacity=".40" '
            f'stroke-width="{2.4 / scale:.3f}"/>' for a in angles)
        body.append(f'<g transform="translate({W / 2} {H + 80})">{petals}</g>')
    body.append(f'<rect width="{W}" height="{H}" fill="url(#fade)"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
            f'viewBox="0 0 {W} {H}"><defs>{"".join(defs)}</defs>{"".join(body)}</svg>')


def main(out):
    with tempfile.TemporaryDirectory() as tmp:
        src, png = f"{tmp}/w.svg", f"{tmp}/w.png"
        with open(src, "w") as f:
            f.write(svg())
        subprocess.run(["rsvg-convert", "-o", png, src], check=True)
        art = np.asarray(Image.open(png).convert("RGB"), dtype=np.float32)
    # dark gradients band like hell on oled, a pinch of grain sorts it
    grain = np.random.default_rng(1).normal(0, 1.5, (H, W, 1))
    Image.fromarray(np.clip(art + grain + .5, 0, 255).astype(np.uint8)).save(out, optimize=True)


if __name__ == "__main__":
    main(sys.argv[1])
