"""Regenerate sift's before/after images by running the real pipeline.

Run once; the output is what gets committed.

    python tools/samples/make_assets.py          # from the repo root, needs JDK 17 + Pillow

Steps:
  1. Download every photo in sources.json (public domain / CC0, Wikimedia
     Commons) into tools/samples/build/in/, refusing any whose sha256 differs
     from the pinned one.
  2. `gradlew :tools:samples:run` grades each with the unmodified
     Pipeline.process, writing the pipeline's own JPEG and a JSON record.
  3. Compose the committed assets from those outputs:
       docs/grade-before-after.webp   the hero pair (HERO below)
       docs/grade-outcomes-<set>.webp one grid per input set: every photo,
                                      labelled with what the pipeline did and
                                      the number that decided it
       results/grade-samples.json     one row per photo: route, gate outcome,
                                      measured LAB shift

Nothing is picked by eye after the fact: the grid shows every source in
sources.json, and HERO is named here, in the script that is committed.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / "tools" / "samples"
BUILD = HERE / "build"
IN_DIR = BUILD / "in"
OUT_DIR = BUILD / "out"

# The only in-domain photo (onboard ISS snapshot under cabin lighting) that the
# pipeline graded and passed every gate on. See results/grade-samples.json.
HERO = "iss072e189112"

HERO_WIDTH = 1440  # displayed at 720 CSS px; 2x for high-density screens
# 5 columns at 1440px reads at 144 CSS px per cell when shown at 720 wide.
TILE = 288
GRID_COLS = 5
GRID_GAP = 6
# Mid-dark neutral: reads as a card on GitHub's light theme and sits flush on
# its dark theme and the dark-only site, so one raster serves all three.
GRID_BG = (36, 39, 44)
WEBP_QUALITY = 78

# Wikimedia asks for a descriptive User-Agent and rejects the urllib default.
USER_AGENT = "sift-sample-assets/1.0 (https://github.com/brettadams0/sift)"

OUTCOME_COLOURS = {
    "graded": (46, 125, 50),
    "fell back": (191, 54, 12),
    "routed out": (69, 90, 100),
}


def fetch_sources() -> list[dict]:
    sources = json.loads((HERE / "sources.json").read_text())
    IN_DIR.mkdir(parents=True, exist_ok=True)
    for src in sources:
        path = IN_DIR / f"{src['slug']}.jpg"
        if not path.exists():
            req = urllib.request.Request(src["url"], headers={"User-Agent": USER_AGENT})
            path.write_bytes(urllib.request.urlopen(req).read())
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != src["sha256"]:
            sys.exit(f"{src['slug']}: sha256 {digest} != pinned {src['sha256']}")
    return sources


def run_pipeline() -> None:
    gradlew = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    subprocess.run(
        [str(gradlew), "-q", "--no-daemon", ":tools:samples:run", f"--args={IN_DIR} {OUT_DIR}"],
        cwd=ROOT,
        check=True,
    )


def outcome(record: dict) -> tuple[str, str]:
    """Classify a record and name the number that decided it."""
    gates = record["gates"]
    if record["profile"] == "NONE":
        a = record["analysisBefore"]
        why = "screenshot" if a["isLikelyScreenshot"] else "document detector"
        return "routed out", why
    if gates["fellBackToOriginal"]:
        # The report attached to a fallback describes the shipped original, so
        # the failing measurements survive only in the reason string:
        # "quality gate failed after 2 attempts: No new clipping (0.014 vs 0.002), ..."
        failed = [
            (name, float(m), float(t))
            for name, m, t in re.findall(r"([A-Za-z ]+?) \(([\d.eE-]+) vs ([\d.eE-]+)\)", gates["fallbackReason"])
        ]
        name, measured, threshold = max(failed, key=lambda f: f[1] / f[2])
        return "fell back", gate_label(name.strip(), measured, threshold)
    # Pillow's bundled font has no Greek, so "dE76" rather than the usual symbol.
    return "graded", f"mean dE76 {record['shift']['meanDeltaE76']:.1f}"


def gate_label(name: str, measured: float, threshold: float) -> str:
    short = {"No new clipping": "clipped", "No shadow crush": "crushed"}.get(name, name.lower())
    if threshold < 0.1:  # gate measured as a fraction of the frame
        return f"{short} {measured * 100:.1f}% > {threshold * 100:.1f}%"
    return f"{short} {measured:.2f} vs {threshold:.2f}"


def summarise(sources: list[dict]) -> list[dict]:
    rows = []
    for src in sources:
        rec = json.loads((OUT_DIR / f"{src['slug']}.json").read_text())
        kind, detail = outcome(rec)
        s = rec["shift"]
        rows.append({
            "slug": src["slug"],
            "set": src["set"],
            "license": src["license"],
            "page": src["page"],
            "width": rec["width"],
            "height": rec["height"],
            "route": rec["contentClass"],
            "outcome": kind,
            "decidedBy": detail,
            "fallbackReason": rec["gates"].get("fallbackReason"),
            "attempts": rec["gates"]["attempts"],
            "meanDeltaE76": round(s["meanDeltaE76"], 2),
            "p95DeltaE76": round(s["p95DeltaE76"], 2),
            "meanDeltaL": round(s["meanDeltaL"], 2),
            "meanDeltaA": round(s["meanDeltaA"], 2),
            "meanDeltaB": round(s["meanDeltaB"], 2),
            "portrait": rec["derived"].get("portrait"),
            # The three inputs to isLikelyDocument, so a misroute is diagnosable.
            "edgeDensity": round(rec["analysisBefore"]["edgeDensity"], 4),
            "meanChroma": round(rec["analysisBefore"]["meanChroma"], 2),
        })
    return rows


def font(size: int) -> ImageFont.ImageFont:
    # Pillow's bundled font, so the output does not depend on installed fonts.
    return ImageFont.load_default(size=size)


def hero() -> Image.Image:
    before = Image.open(IN_DIR / f"{HERO}.jpg").convert("RGB")
    after = Image.open(OUT_DIR / f"{HERO}.graded.jpg").convert("RGB")
    gap = 8
    half = (HERO_WIDTH - gap) // 2
    h = round(before.height * half / before.width)
    canvas = Image.new("RGB", (HERO_WIDTH, h), GRID_BG)
    draw = ImageDraw.Draw(canvas)
    for i, (img, label) in enumerate(((before, "before"), (after, "after (sift)"))):
        x = i * (half + gap)
        canvas.paste(img.resize((half, h), Image.Resampling.LANCZOS), (x, 0))
        f = font(30)
        box = draw.textbbox((0, 0), label, font=f)
        draw.rectangle((x + 16, 16, x + 40 + box[2], 28 + box[3] + 16), fill=(0, 0, 0))
        draw.text((x + 28, 22), label, font=f, fill=(255, 255, 255))
    return canvas


def grid(rows: list[dict]) -> Image.Image:
    order = {"graded": 0, "fell back": 1, "routed out": 2}
    rows = sorted(rows, key=lambda r: (order[r["outcome"]], r["slug"]))
    label_h = 78
    cell_w, cell_h = TILE, TILE + label_h
    n_rows = -(-len(rows) // GRID_COLS)
    canvas = Image.new(
        "RGB",
        (GRID_COLS * cell_w + (GRID_COLS + 1) * GRID_GAP, n_rows * cell_h + (n_rows + 1) * GRID_GAP),
        GRID_BG,
    )
    draw = ImageDraw.Draw(canvas)
    for i, r in enumerate(rows):
        x = GRID_GAP + (i % GRID_COLS) * (cell_w + GRID_GAP)
        y = GRID_GAP + (i // GRID_COLS) * (cell_h + GRID_GAP)
        # Graded: show the output. Otherwise the output is the source, unchanged.
        graded = r["outcome"] == "graded"
        src = OUT_DIR / f"{r['slug']}.graded.jpg" if graded else IN_DIR / f"{r['slug']}.jpg"
        img = Image.open(src).convert("RGB")
        side = min(img.size)
        img = img.crop(((img.width - side) // 2, (img.height - side) // 2,
                        (img.width + side) // 2, (img.height + side) // 2))
        canvas.paste(img.resize((TILE, TILE), Image.Resampling.LANCZOS), (x, y))
        draw.rectangle((x, y + TILE, x + cell_w - 1, y + cell_h - 1), fill=OUTCOME_COLOURS[r["outcome"]])
        draw.text((x + 14, y + TILE + 8), r["outcome"], font=font(30), fill=(255, 255, 255))
        draw.text((x + 14, y + TILE + 44), r["decidedBy"], font=font(24), fill=(255, 255, 255))
    return canvas


def main() -> None:
    sources = fetch_sources()
    run_pipeline()
    rows = summarise(sources)

    (ROOT / "docs").mkdir(exist_ok=True)
    (ROOT / "results").mkdir(exist_ok=True)
    hero().save(ROOT / "docs" / "grade-before-after.webp", quality=WEBP_QUALITY, method=6)
    for set_name in sorted({r["set"] for r in rows}):
        subset = [r for r in rows if r["set"] == set_name]
        grid(subset).save(ROOT / "docs" / f"grade-outcomes-{set_name}.webp", quality=WEBP_QUALITY, method=6)
    (ROOT / "results" / "grade-samples.json").write_text(json.dumps(rows, indent=2) + "\n")

    for kind in ("graded", "fell back", "routed out"):
        print(f"{kind:>10}: {sum(r['outcome'] == kind for r in rows)} of {len(rows)}")


if __name__ == "__main__":
    main()
