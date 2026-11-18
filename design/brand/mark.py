# SPDX-License-Identifier: GPL-3.0-or-later
"""Geometry of the Marquee mark ("reel lattice").

A round emblem in one colour. A sea of overlapping film reels, drawn like seigaiha
waves: each reel is wound with bands of film around a windowed hub, and each row
hides part of the row behind it. An empty reel rises over them as the sun, under an
arc of sign bulbs. Nothing is outlined; the gaps between the shapes do the drawing.

All functions return shapely geometry in a 100 x 100 box with y pointing down.
"""
from __future__ import annotations

import math

from shapely.geometry import Point, Polygon
from shapely.ops import unary_union

Q = 24                 # arc resolution (segments per quarter turn)
C = (50.0, 50.0)       # centre of the emblem
RADIUS = 45.0          # emblem radius

REEL_R = 25.0          # reel radius
BANDS, BAND, GAP = 2, 3.9, 3.1   # film wound on each reel: count, width, spacing
SUN = ((50.0, 24.0), 9.5)        # the empty reel in the sky
BULB_R, BULB_ORBIT, BULB_STEP = 2.2, 42.7, 13   # sign bulbs: radius, orbit, degrees apart
HUB_MIN_VISIBLE = 0.40           # show a hub only when this share of it is in view

# Rows of reels, front row first: (centre, hub rotation in degrees).
ROWS = [
    [((4, 108), 0), ((50, 108), 30), ((96, 108), 10)],
    [((27, 86), 20), ((73, 86), 56)],
    [((4, 64), 40), ((50, 64), 0), ((96, 64), 15)],
]


def disc(r, c=C):
    return Point(*c).buffer(r, quad_segs=Q)


def ring(r_out, r_in, c=C):
    return disc(r_out, c).difference(disc(r_in, c))


def polar(r, deg, c=C):
    return (c[0] + r * math.cos(math.radians(deg)), c[1] + r * math.sin(math.radians(deg)))


def hub(c, r, turn=0.0, windows=5):
    """Reel hub: a disc with round windows and a spindle hole, all negative space."""
    g = disc(r, c)
    for i in range(windows):
        g = g.difference(disc(r * 0.235, polar(r * 0.57, -90 + turn + 360 / windows * i, c)))
    return g.difference(disc(r * 0.115, c))


def _clean(g, min_area=1.2):
    """Drop slivers left by the boolean operations."""
    polys = [p for p in getattr(g, "geoms", [g]) if p.geom_type == "Polygon" and p.area >= min_area]
    return unary_union(polys) if polys else Polygon()


def emblem():
    """The full mark."""
    frame = disc(RADIUS)
    hub_r = REEL_R - BANDS * (BAND + GAP)
    parts, mask = [], Polygon()
    for row in ROWS:
        row_parts = []
        for c, turn in row:
            film = [ring(REEL_R - i * (BAND + GAP), REEL_R - i * (BAND + GAP) - BAND, c) for i in range(BANDS)]
            seen = disc(hub_r, c).intersection(frame).difference(mask).area / disc(hub_r, c).area
            if seen >= HUB_MIN_VISIBLE:
                film.append(hub(c, hub_r, turn))
            else:  # hub mostly hidden or off the edge: keep winding film to the centre
                r = hub_r
                while r > BAND:
                    film.append(ring(r, r - BAND, c))
                    r -= BAND + GAP
            row_parts.append(unary_union(film))
        parts.append(unary_union(row_parts).difference(mask))
        mask = mask.union(unary_union([disc(REEL_R + GAP, c) for c, _ in row]))
    sea = _clean(unary_union(parts).intersection(frame))
    (sun_c, sun_r) = SUN
    bulbs = unary_union([disc(BULB_R, polar(BULB_ORBIT, a))
                         for a in range(-90 - 6 * BULB_STEP, -90 + 6 * BULB_STEP + 1, BULB_STEP)])
    bulbs = _clean(bulbs.difference(mask.buffer(BULB_R * 1.2)), min_area=BULB_R * BULB_R * 3.0)
    return unary_union([sea, hub(sun_c, sun_r), bulbs])


def reel(r=44.0):
    """The reel alone, for sizes too small for the full mark. Same drawing as the sun."""
    return hub(C, r)
