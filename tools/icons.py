#!/usr/bin/env python3
"""
Ours v7 icon set — one source of geometry, two outputs.

    python3 tools/icons.py

Writes:
    app/src/main/res/drawable/ic_*.xml     Android vector drawables (the app)
    design/v7/sprite.js                    SVG symbols (the mockups)

## Why redraw at all

The set this replaces is Bootstrap Icons: **filled silhouettes on a 16px grid**, rendered
in this app at 13–24dp. Two consequences that are visible on a real phone:

  * Detail collapses. `bi_trash` is a lid, a rim, a tapered body and three tick marks all
    as solid fill — at 13dp the lid merges into the body and it reads as a grey blob.
  * The weight is inconsistent. A 16px-grid glyph scaled to 24dp has ~1.5x the apparent
    stroke of one drawn at 24, so the set never looks like one family.

This set is drawn on a **24px grid as single-weight strokes**, 1.75dp, round caps and
joins. Stroke geometry is the right choice here for three reasons: the weight is one number
rather than an emergent property of each outline, it stays crisp scaled down because the
stroke does not thin, and a filled variant can be derived where one is genuinely needed
(the five selected nav tabs) rather than being a second hand-drawn set.

Android supports `strokeColor`/`strokeWidth`/`strokeLineCap`/`strokeLineJoin` on `<path>`
from API 21; this app is minSdk 26, so there is no compatibility cost. Compose's
`Icon(painter, tint = …)` applies a tint to the whole drawable, so stroke icons tint
exactly as the filled ones did.

## The keylines every glyph obeys

    canvas        24 x 24
    live area     20 x 20   (2dp clear on every side)
    circle        d 19
    square        18 x 18
    stroke        1.75, round cap, round join
    corner        2.0 outer, 1.25 inner
    terminals     land on whole or half units

Anything that breaks a keyline says why, in a comment beside it.
"""

import math
import os
import re

STROKE = 1.75
CANVAS = 24

# ─────────────────────────────────────────────────────────────────────────────
# Helpers for the two glyphs worth computing rather than drawing by hand
# ─────────────────────────────────────────────────────────────────────────────

def _f(v: float) -> str:
    """Trim a coordinate to 2dp and drop trailing zeros — pathData stays readable."""
    s = f'{v:.2f}'.rstrip('0').rstrip('.')
    return '0' if s in ('', '-0') else s


def gear(teeth=6, ro=9.7, ri=7.5, tooth=15.0, cx=12.0, cy=12.0) -> str:
    """A cog, computed.

    Hand-drawing an eight-tooth gear on a 24 grid produces teeth of visibly different
    widths — the error is small per tooth and obvious once they are in a ring. Six broad
    teeth also survive 13dp, where eight narrow ones alias into a circle.
    """
    step = 360.0 / teeth
    half = tooth / 2.0
    pts = []
    for i in range(teeth):
        c = i * step
        # outer arc across the tooth, then drop to the root circle for the gap
        pts.append(('outer', c - half, c + half))
        pts.append(('inner', c + half, c + step - half))

    d = []
    for kind, a0, a1 in pts:
        r = ro if kind == 'outer' else ri
        x0 = cx + r * math.cos(math.radians(a0 - 90))
        y0 = cy + r * math.sin(math.radians(a0 - 90))
        x1 = cx + r * math.cos(math.radians(a1 - 90))
        y1 = cy + r * math.sin(math.radians(a1 - 90))
        if not d:
            d.append(f'M{_f(x0)} {_f(y0)}')
        else:
            d.append(f'L{_f(x0)} {_f(y0)}')
        d.append(f'A{_f(r)} {_f(r)} 0 0 1 {_f(x1)} {_f(y1)}')
    d.append('Z')
    return ' '.join(d)


def pie_slice(a0, a1, r=9.3, cx=12.0, cy=12.0, closed=True) -> str:
    """A filled wedge from a0° to a1°, measured clockwise from twelve o'clock."""
    x0 = cx + r * math.cos(math.radians(a0 - 90))
    y0 = cy + r * math.sin(math.radians(a0 - 90))
    x1 = cx + r * math.cos(math.radians(a1 - 90))
    y1 = cy + r * math.sin(math.radians(a1 - 90))
    large = 1 if (a1 - a0) > 180 else 0
    d = f'M{_f(cx)} {_f(cy)} L{_f(x0)} {_f(y0)} A{_f(r)} {_f(r)} 0 {large} 1 {_f(x1)} {_f(y1)}'
    return d + ' Z' if closed else d


def circle(cx, cy, r) -> str:
    """A full circle as two arcs — one arc cannot close a circle in SVG."""
    return (f'M{_f(cx - r)} {_f(cy)} '
            f'A{_f(r)} {_f(r)} 0 1 0 {_f(cx + r)} {_f(cy)} '
            f'A{_f(r)} {_f(r)} 0 1 0 {_f(cx - r)} {_f(cy)} Z')


# ─────────────────────────────────────────────────────────────────────────────
# The set
#
# Each entry is (name, [subpaths]) for a stroke glyph, or
#             (name, [subpaths], 'fill') for a filled one.
# Filled entries may carry 'evenOdd' so an inner subpath knocks a hole.
# ─────────────────────────────────────────────────────────────────────────────

ICONS: dict[str, tuple] = {}


def stroke_icon(name, *paths):
    ICONS[name] = ('stroke', list(paths), None)


def fill_icon(name, *paths, fill_type='nonZero'):
    ICONS[name] = ('fill', list(paths), fill_type)


# ── Navigation ──────────────────────────────────────────────────────────────

stroke_icon('home',
    'M3.4 10.9 L12 4.1 L20.6 10.9',
    'M5.9 9.6 V18.2 A1.8 1.8 0 0 0 7.7 20 H16.3 A1.8 1.8 0 0 0 18.1 18.2 V9.6')
fill_icon('home_fill',
    'M12 3.7 L2.8 11.0 H5.5 V18.4 A1.8 1.8 0 0 0 7.3 20.2 H16.7 '
    'A1.8 1.8 0 0 0 18.5 18.4 V11.0 H21.2 Z')

# A receipt, torn along the bottom. The tear is the whole idea: it is what makes this a
# printed slip rather than a document, and it is the mark this app is named after.
stroke_icon('receipt',
    'M6 4.5 A1.5 1.5 0 0 1 7.5 3 H16.5 A1.5 1.5 0 0 1 18 4.5 V21 L15 19.2 '
    'L12 21 L9 19.2 L6 21 Z',
    'M9.2 8 H14.8', 'M9.2 11.5 H14.8', 'M9.2 15 H13')
fill_icon('receipt_fill',
    'M6 4.5 A1.5 1.5 0 0 1 7.5 3 H16.5 A1.5 1.5 0 0 1 18 4.5 V21 L15 19.2 '
    'L12 21 L9 19.2 L6 21 Z',
    'M9.2 7.1 H14.8 A0.9 0.9 0 0 1 14.8 8.9 H9.2 A0.9 0.9 0 0 1 9.2 7.1 Z',
    'M9.2 10.6 H14.8 A0.9 0.9 0 0 1 14.8 12.4 H9.2 A0.9 0.9 0 0 1 9.2 10.6 Z',
    'M9.2 14.1 H13 A0.9 0.9 0 0 1 13 15.9 H9.2 A0.9 0.9 0 0 1 9.2 14.1 Z',
    fill_type='evenOdd')

# Summary. A ring with one slice lifted out, rather than a solid pie: the ring reads at
# 13dp where a pie's dividing lines do not.
stroke_icon('chart',
    'M12 3.1 A8.9 8.9 0 1 0 20.9 12 H12 Z',
    'M14.1 3.4 A8.9 8.9 0 0 1 20.6 9.9 H14.1 Z')
fill_icon('chart_fill',
    pie_slice(0, 268, r=8.9),
    pie_slice(276, 356, r=8.9))

stroke_icon('wallet',
    'M3.5 8.5 A2 2 0 0 1 5.5 6.5 H18.5 A2 2 0 0 1 20.5 8.5 V17.5 '
    'A2 2 0 0 1 18.5 19.5 H5.5 A2 2 0 0 1 3.5 17.5 Z',
    # The card tucked behind it — this is what tells a wallet from a plain box.
    'M6.5 6.5 V5.4 A1.4 1.4 0 0 1 8.2 4 L17 5.6',
    'M20.5 11.5 H16.2 A1.6 1.6 0 0 0 16.2 14.7 H20.5')
# The filled wallet needs its clasp knocked out, not just a dot laid on top: a solid
# rounded rectangle with a spot in it reads as a box with a hole, which is what the first
# pass drew. evenOdd nests three subpaths — body, then the clasp pocket as a hole, then the
# stud filled back in inside it — so the silhouette is legibly a wallet at 13dp.
fill_icon('wallet_fill',
    'M3.5 8.5 A2 2 0 0 1 5.5 6.5 H18.5 A2 2 0 0 1 20.5 8.5 V17.5 '
    'A2 2 0 0 1 18.5 19.5 H5.5 A2 2 0 0 1 3.5 17.5 Z',
    'M15.1 11 H20.5 V15.2 H15.1 A2.1 2.1 0 0 1 15.1 11 Z',
    circle(17.6, 13.1, 1.15),
    fill_type='evenOdd')

stroke_icon('gear', gear(), circle(12, 12, 3.1))
fill_icon('gear_fill', gear(), circle(12, 12, 3.1), fill_type='evenOdd')

# ── Actions ─────────────────────────────────────────────────────────────────

stroke_icon('plus', 'M12 5 V19', 'M5 12 H19')
stroke_icon('x', 'M6 6 L18 18', 'M18 6 L6 18')
stroke_icon('check', 'M5 12.8 L9.6 17.4 L19 8')
stroke_icon('check_circle', circle(12, 12, 8.9), 'M8 12.3 L10.9 15.2 L16 10')

# The bin, redrawn. This is the glyph that prompted the set.
#
# The old one put a lid, a rim, a tapered body and three ticks into solid fill on a 16
# grid; at 13dp — the size the Trash row and the delete button actually use — the lid
# merged with the body. Here the lid is one stroke, the handle is a separate arch above
# it, and the body tapers slightly inward so it reads as a container rather than a box.
# Two ribs, not three: at this size the third closes the gaps up.
stroke_icon('trash',
    'M4.2 7.1 H19.8',
    'M9.6 7.1 V5.4 A1.2 1.2 0 0 1 10.8 4.2 H13.2 A1.2 1.2 0 0 1 14.4 5.4 V7.1',
    'M6.6 7.1 L7.4 18.9 A1.8 1.8 0 0 0 9.2 20.6 H14.8 A1.8 1.8 0 0 0 16.6 18.9 L17.4 7.1',
    'M10.6 10.7 V16.6', 'M13.4 10.7 V16.6')

stroke_icon('search',
    circle(11, 11, 6.6),
    'M15.8 15.8 L20.4 20.4')
stroke_icon('tag',
    'M4.4 11.3 V5.9 A1.5 1.5 0 0 1 5.9 4.4 H11.3 A1.5 1.5 0 0 1 12.4 4.85 '
    'L19.6 12.05 A1.5 1.5 0 0 1 19.6 14.15 L14.15 19.6 A1.5 1.5 0 0 1 12.05 19.6 '
    'L4.85 12.4 A1.5 1.5 0 0 1 4.4 11.3 Z',
    circle(8.4, 8.4, 1.35))
stroke_icon('question',
    circle(12, 12, 8.9),
    'M9.5 9.4 A2.6 2.6 0 1 1 12 12.6 V14.1',
    'M12 17.2 V17.3')
stroke_icon('download',
    'M12 4.2 V15.2', 'M7.6 11 L12 15.4 L16.4 11)'.replace(')', ''),
    'M4.6 18.9 H19.4')

# ── Arrows and chevrons ─────────────────────────────────────────────────────

stroke_icon('arrow_left', 'M19 12 H5.4', 'M11 5.4 L4.4 12 L11 18.6')
stroke_icon('arrow_up', 'M12 19 V5.4', 'M5.4 11 L12 4.4 L18.6 11')
stroke_icon('arrow_down', 'M12 5 V18.6', 'M18.6 13 L12 19.6 L5.4 13')
stroke_icon('arrow_left_right',
    'M4.4 9 H19.6', 'M16.6 6 L19.6 9 L16.6 12',
    'M19.6 16 H4.4', 'M7.4 13 L4.4 16 L7.4 19')
stroke_icon('chevron_left', 'M15.2 5.2 L8.4 12 L15.2 18.8')
stroke_icon('chevron_right', 'M8.8 5.2 L15.6 12 L8.8 18.8')
stroke_icon('chevron_up', 'M5.2 15.2 L12 8.4 L18.8 15.2')
stroke_icon('chevron_down', 'M5.2 8.8 L12 15.6 L18.8 8.8')

# Sync. Two arcs chasing each other — a single circular arrow reads as "reload one
# thing", and this app is reconciling two phones.
stroke_icon('arrow_repeat',
    'M20 12 A8 8 0 0 1 6.6 17.9',
    'M4 12 A8 8 0 0 1 17.4 6.1',
    'M17.4 3 V6.4 H14', 'M6.6 21 V17.6 H10')

# ── Status ──────────────────────────────────────────────────────────────────

stroke_icon('cloud_check',
    'M7.2 18.4 A4.2 4.2 0 0 1 7.5 10 A5.6 5.6 0 0 1 18.2 10.5 A3.9 3.9 0 0 1 18.4 18.4 Z',
    'M9.4 14.2 L11.4 16.2 L15 12.6')
stroke_icon('cloud_slash',
    'M7.2 18.4 A4.2 4.2 0 0 1 7.5 10 A5.6 5.6 0 0 1 18.2 10.5 A3.9 3.9 0 0 1 18.4 18.4 Z',
    'M4.6 4.6 L19.4 19.4')
stroke_icon('bluetooth',
    'M8.4 7.6 L15.6 16.4 L12 20 V4 L15.6 7.6 L8.4 16.4')
stroke_icon('warning',
    'M10.6 4.7 A1.6 1.6 0 0 1 13.4 4.7 L20.4 17.6 A1.6 1.6 0 0 1 19 20 H5 '
    'A1.6 1.6 0 0 1 3.6 17.6 Z',
    'M12 9.6 V14', 'M12 17 V17.1')
stroke_icon('shield_lock',
    'M12 3.4 L19.4 6 V12 C19.4 16.4 16.2 19.4 12 20.8 C7.8 19.4 4.6 16.4 4.6 12 V6 Z',
    'M9.7 12.1 V10.6 A2.3 2.3 0 0 1 14.3 10.6 V12.1',
    'M9.4 12.1 H14.6 V16 H9.4 Z')
stroke_icon('lock',
    'M5.6 11.5 H18.4 V19.4 A1.2 1.2 0 0 1 17.2 20.6 H6.8 A1.2 1.2 0 0 1 5.6 19.4 Z',
    'M8.6 11.5 V8.2 A3.4 3.4 0 0 1 15.4 8.2 V11.5')
stroke_icon('dots', circle(5.2, 12, 1.5), circle(12, 12, 1.5), circle(18.8, 12, 1.5))

# ── Objects ─────────────────────────────────────────────────────────────────

stroke_icon('envelope',
    'M3.4 7.6 A1.6 1.6 0 0 1 5 6 H19 A1.6 1.6 0 0 1 20.6 7.6 V16.4 '
    'A1.6 1.6 0 0 1 19 18 H5 A1.6 1.6 0 0 1 3.4 16.4 Z',
    'M3.8 7.2 L12 13 L20.2 7.2')
stroke_icon('inbox',
    'M3.6 13.4 L6.4 5.6 A1.5 1.5 0 0 1 7.8 4.6 H16.2 A1.5 1.5 0 0 1 17.6 5.6 '
    'L20.4 13.4 V17.9 A1.5 1.5 0 0 1 18.9 19.4 H5.1 A1.5 1.5 0 0 1 3.6 17.9 Z',
    'M3.6 13.4 H8.6 L9.8 15.6 H14.2 L15.4 13.4 H20.4')
stroke_icon('inbox_stack',
    'M3.6 12.2 L5.8 6.4 A1.4 1.4 0 0 1 7.1 5.5 H16.9 A1.4 1.4 0 0 1 18.2 6.4 '
    'L20.4 12.2 H15.4 L14.3 14.2 H9.7 L8.6 12.2 Z',
    'M4.6 15.4 H8.6 L9.7 17.4 H14.3 L15.4 15.4 H19.4 V18.6 '
    'A1.4 1.4 0 0 1 18 20 H6 A1.4 1.4 0 0 1 4.6 18.6 Z')
stroke_icon('people',
    circle(9.2, 8.4, 3.4),
    'M3.4 19.6 A5.8 5.8 0 0 1 15 19.6',
    'M15.6 5.6 A3.4 3.4 0 0 1 15.6 11.2',
    'M17 14.6 A5.8 5.8 0 0 1 20.6 19.6')
stroke_icon('camera',
    'M3.6 9.6 A1.6 1.6 0 0 1 5.2 8 H7.4 L8.8 5.4 A1.2 1.2 0 0 1 9.9 4.8 H14.1 '
    'A1.2 1.2 0 0 1 15.2 5.4 L16.6 8 H18.8 A1.6 1.6 0 0 1 20.4 9.6 V17.6 '
    'A1.6 1.6 0 0 1 18.8 19.2 H5.2 A1.6 1.6 0 0 1 3.6 17.6 Z',
    circle(12, 13.4, 3.4))
stroke_icon('folder',
    'M3.6 7.4 A1.6 1.6 0 0 1 5.2 5.8 H9.4 L11.4 8.2 H18.8 A1.6 1.6 0 0 1 20.4 9.8 '
    'V17.6 A1.6 1.6 0 0 1 18.8 19.2 H5.2 A1.6 1.6 0 0 1 3.6 17.6 Z')
stroke_icon('folder_open',
    'M3.6 7.4 A1.6 1.6 0 0 1 5.2 5.8 H9.4 L11.4 8.2 H17.4 A1.6 1.6 0 0 1 19 9.8 V11',
    'M3.6 7.8 V17.6 A1.6 1.6 0 0 0 5.2 19.2 H18.4 L21 11.6 A1 1 0 0 0 20 10.3 H7.6 '
    'A1.4 1.4 0 0 0 6.3 11.3 Z')
stroke_icon('send_check',
    'M3.4 12 L20.2 4.6 L15.6 12',
    'M3.4 12 L9.8 14.2 L11 20.4 L15.6 12',
    'M14.4 17.6 L16.6 19.8 L20.6 15.8')
stroke_icon('graph_up',
    'M4 4.4 V19.6 H20',
    'M6.8 16.4 L11 11.4 L14.2 14 L19.2 7.6')
stroke_icon('credit_card',
    'M3.4 8 A1.6 1.6 0 0 1 5 6.4 H19 A1.6 1.6 0 0 1 20.6 8 V16 '
    'A1.6 1.6 0 0 1 19 17.6 H5 A1.6 1.6 0 0 1 3.4 16 Z',
    'M3.4 10.4 H20.6',
    'M6.6 14.2 H10.2')
stroke_icon('bank',
    'M3.6 9.6 L12 4.4 L20.4 9.6',
    'M5.6 9.6 V17.6', 'M9.8 9.6 V17.6', 'M14.2 9.6 V17.6', 'M18.4 9.6 V17.6',
    'M3.4 19.6 H20.6')
stroke_icon('cash',
    'M3.4 8.6 A1.6 1.6 0 0 1 5 7 H19 A1.6 1.6 0 0 1 20.6 8.6 V15.4 '
    'A1.6 1.6 0 0 1 19 17 H5 A1.6 1.6 0 0 1 3.4 15.4 Z',
    circle(12, 12, 2.4),
    'M6.4 12 V12.1', 'M17.6 12 V12.1')

# ── Categories ──────────────────────────────────────────────────────────────

# Food: a cup with steam. The steam is what stops it reading as a bucket.
stroke_icon('cup',
    'M4.6 9.4 H16.4 V15 A4.4 4.4 0 0 1 12 19.4 H9 A4.4 4.4 0 0 1 4.6 15 Z',
    'M16.4 11 H18 A2.2 2.2 0 0 1 18 15.4 H16.4',
    'M8.4 6.6 C8.4 5.6 9.6 5.4 9.6 4.2', 'M12.4 6.6 C12.4 5.6 13.6 5.4 13.6 4.2')
stroke_icon('basket',
    'M3.4 9.6 H20.6 L18.9 18.2 A1.8 1.8 0 0 1 17.1 19.6 H6.9 A1.8 1.8 0 0 1 5.1 18.2 Z',
    'M8.2 9.6 L10.4 4.6', 'M15.8 9.6 L13.6 4.6',
    'M9.6 13 V16.2', 'M14.4 13 V16.2')
stroke_icon('fuel',
    'M4.6 20.4 V6 A1.6 1.6 0 0 1 6.2 4.4 H12.4 A1.6 1.6 0 0 1 14 6 V20.4',
    'M3.4 20.4 H15.2',
    'M6.8 7.6 H11.8 V11 H6.8 Z',
    'M14 10 H17.4 A1.6 1.6 0 0 1 19 11.6 V16.4 A1.6 1.6 0 0 0 20.6 18 V18',
    'M17 10 V7.4')
stroke_icon('bag',
    'M4.8 8.4 H19.2 L20.2 18.4 A1.8 1.8 0 0 1 18.4 20.4 H5.6 A1.8 1.8 0 0 1 3.8 18.4 Z',
    'M8.6 8.4 V6.6 A3.4 3.4 0 0 1 15.4 6.6 V8.4')
stroke_icon('bolt', 'M13.4 3.4 L6.2 13.4 H11.2 L10.6 20.6 L17.8 10.6 H12.8 Z')
stroke_icon('house_door',
    'M3.4 10.9 L12 4.1 L20.6 10.9',
    'M5.9 9.6 V18.4 A1.6 1.6 0 0 0 7.5 20 H16.5 A1.6 1.6 0 0 0 18.1 18.4 V9.6',
    'M10.2 20 V14.4 H13.8 V20')
stroke_icon('heart',
    'M12 20 C12 20 3.6 15.2 3.6 9.8 A4.4 4.4 0 0 1 12 8.2 A4.4 4.4 0 0 1 20.4 9.8 '
    'C20.4 15.2 12 20 12 20 Z',
    # Pulled in and shortened from the first pass. The trace has to clear the heart's own
    # stroke on both sides or the two merge into one blob at 13dp, which is the size the
    # category avatar actually draws — and a heart-shaped blob reads as "favourite".
    'M7.6 11.9 H9.9 L11.1 10.2 L12.8 13.2 L13.9 11.9 H16.4')
stroke_icon('book',
    'M4.4 5.4 A1.4 1.4 0 0 1 5.8 4 H11 A1.6 1.6 0 0 1 12 4.6 A1.6 1.6 0 0 1 13 4 '
    'H18.2 A1.4 1.4 0 0 1 19.6 5.4 V17.4 A1.4 1.4 0 0 1 18.2 18.8 H13.4 '
    'A1.6 1.6 0 0 0 12 19.6 A1.6 1.6 0 0 0 10.6 18.8 H5.8 A1.4 1.4 0 0 1 4.4 17.4 Z',
    'M12 5.4 V19.2')
stroke_icon('film',
    'M3.6 7 A1.6 1.6 0 0 1 5.2 5.4 H18.8 A1.6 1.6 0 0 1 20.4 7 V17 '
    'A1.6 1.6 0 0 1 18.8 18.6 H5.2 A1.6 1.6 0 0 1 3.6 17 Z',
    'M8.2 5.4 V18.6', 'M15.8 5.4 V18.6',
    'M3.6 12 H8.2', 'M15.8 12 H20.4')
stroke_icon('plane',
    'M10.4 4.6 A1.6 1.6 0 0 1 13.6 4.6 L14 10.6 L20.6 14.2 V16.6 L14 15 L13.4 18.6 '
    'L15.8 20.4 V21.4 L12 20.4 L8.2 21.4 V20.4 L10.6 18.6 L10 15 L3.4 16.6 V14.2 '
    'L10 10.6 Z')
# Savings and investments: a line that rises, with an arrowhead.
#
# The first pass drew four bars plus a connecting line, and the line dipped before it rose
# — so at any size it read as a crown or a letter M rather than as growth. A category glyph
# has one job, and for this one the job is "up". The arrowhead is what makes the direction
# survive at 13dp, where the slope alone does not.
stroke_icon('chart_arrow',
    'M4.4 16.8 L9.6 11.6 L13.4 15.4 L19.6 8',
    'M14.8 8 H19.6 V12.8')

# ─────────────────────────────────────────────────────────────────────────────
# Legacy names, so nothing has to be renamed at every call site at once
# ─────────────────────────────────────────────────────────────────────────────

ALIASES = {
    'house': 'home', 'house-fill': 'home_fill', 'house-door': 'house_door',
    'receipt-cutoff': 'receipt_fill', 'pie-chart': 'chart', 'pie-chart-fill': 'chart_fill',
    'wallet2': 'wallet', 'wallet-fill': 'wallet_fill', 'gear-fill': 'gear_fill',
    'plus-lg': 'plus', 'x-lg': 'x', 'check-lg': 'check', 'check-circle': 'check_circle',
    'question-circle': 'question', 'arrow-left': 'arrow_left', 'arrow-up': 'arrow_up',
    'arrow-down': 'arrow_down', 'arrow-left-right': 'arrow_left_right',
    'arrow-repeat': 'arrow_repeat', 'chevron-left': 'chevron_left',
    'chevron-right': 'chevron_right', 'chevron-up': 'chevron_up',
    'chevron-down': 'chevron_down', 'cloud-check': 'cloud_check',
    'cloud-slash': 'cloud_slash', 'exclamation-triangle': 'warning',
    'shield-lock': 'shield_lock', 'three-dots': 'dots', 'inboxes': 'inbox_stack',
    'send-check': 'send_check', 'graph-up': 'graph_up', 'graph-up-arrow': 'chart_arrow',
    'credit-card': 'credit_card', 'cash-coin': 'cash', 'cup-hot': 'cup',
    'fuel-pump': 'fuel', 'lightning-charge': 'bolt', 'heart-pulse': 'heart',
    'airplane': 'plane', 'folder2': 'folder', 'folder2-open': 'folder_open',
    'emoji-neutral': 'question',
}

# ─────────────────────────────────────────────────────────────────────────────
# Output
# ─────────────────────────────────────────────────────────────────────────────

VECTOR_HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  Ours v7 icon set. Generated by tools/icons.py — edit that, not this.

  24dp grid, 20dp live area, {stroke}dp stroke, round caps and joins. Stroke geometry
  rather than filled outlines, so the whole set carries one weight and stays crisp at the
  13dp this app draws icons at. Tints correctly through Compose's Icon(tint = …), which
  applies a colour filter to the rendered drawable rather than to fillColor alone.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
'''

STROKE_PATH = '''    <path
        android:pathData="{d}"
        android:fillColor="#00000000"
        android:strokeColor="#FF000000"
        android:strokeWidth="{stroke}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
'''

FILL_PATH = '''    <path
        android:pathData="{d}"
        android:fillColor="#FF000000"
        android:fillType="{ft}" />
'''


def write_drawables(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    written = []
    for name, (kind, paths, ft) in ICONS.items():
        body = ''
        if kind == 'stroke':
            for d in paths:
                body += STROKE_PATH.format(d=d, stroke=STROKE)
        else:
            # One <path> holding every subpath, so fillType='evenOdd' can knock holes.
            joined = ' '.join(paths)
            body += FILL_PATH.format(d=joined, ft=ft or 'nonZero')
        xml = VECTOR_HEAD.format(stroke=STROKE) + body + '</vector>\n'
        path = os.path.join(out_dir, f'ic_{name}.xml')
        with open(path, 'w') as fh:
            fh.write(xml)
        written.append(f'ic_{name}')
    return written


def write_sprite(out_path):
    """SVG symbols for the design pages, from the same geometry.

    Presentation attributes go on each <path> rather than in the stylesheet: an attribute
    on the element beats an inherited CSS value, so a page rule like
    `.avatar svg { fill: currentColor }` cannot accidentally flood a stroke glyph.
    """
    syms = []
    for name, (kind, paths, ft) in ICONS.items():
        if kind == 'stroke':
            body = ''.join(
                f'<path d="{d}" fill="none" stroke="currentColor" stroke-width="{STROKE}"'
                f' stroke-linecap="round" stroke-linejoin="round"/>' for d in paths)
        else:
            rule = 'evenodd' if ft == 'evenOdd' else 'nonzero'
            body = (f'<path d="{" ".join(paths)}" fill="currentColor" stroke="none"'
                    f' fill-rule="{rule}"/>')
        syms.append(f'<symbol id="i-{name}" viewBox="0 0 24 24">{body}</symbol>')

    for legacy, target in ALIASES.items():
        if target not in ICONS:
            raise SystemExit(f'alias {legacy} points at unknown icon {target}')
        syms.append(f'<symbol id="i-{legacy}" viewBox="0 0 24 24">'
                    f'<use href="#i-{target}"/></symbol>')

    sprite = ('<svg width="0" height="0" style="position:absolute" aria-hidden="true">'
              '<defs>' + ''.join(syms) + '</defs></svg>')

    import json
    js = ('/* Ours v7 — icon sprite. Generated by tools/icons.py; edit that, not this.\n'
          f'   {len(ICONS)} glyphs + {len(ALIASES)} legacy aliases, on a 24 grid at '
          f'{STROKE}dp stroke.\n'
          '   The same geometry compiles to app/src/main/res/drawable/ic_*.xml, so a glyph in\n'
          '   a mockup is the glyph that ships. Injected by script rather than fetched:\n'
          '   fetch() is blocked on file:// and these pages open straight from disk. */\n'
          '(function () {\n'
          '  var SPRITE = ' + json.dumps(sprite) + ';\n'
          '  function inject() {\n'
          '    var d = document.createElement("div");\n'
          '    d.style.cssText = "position:absolute;width:0;height:0;overflow:hidden";\n'
          '    d.innerHTML = SPRITE;\n'
          '    document.body.insertBefore(d, document.body.firstChild);\n'
          '  }\n'
          '  if (document.readyState === "loading") '
          'document.addEventListener("DOMContentLoaded", inject);\n'
          '  else inject();\n'
          '})();\n')
    with open(out_path, 'w') as fh:
        fh.write(js)
    return len(syms)


if __name__ == '__main__':
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    drawables = write_drawables(os.path.join(root, 'app/src/main/res/drawable'))
    n = write_sprite(os.path.join(root, 'design/v7/sprite.js'))
    print(f'{len(drawables)} drawables written to app/src/main/res/drawable/')
    print(f'{n} sprite symbols written to design/v7/sprite.js')
    bad = [d for d in drawables if not re.fullmatch(r'ic_[a-z0-9_]+', d)]
    if bad:
        raise SystemExit(f'illegal resource names: {bad}')
