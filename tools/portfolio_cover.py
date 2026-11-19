#!/usr/bin/env python3
"""Photographic A/B portfolio cover from genuine Sift production JPEG outputs.

No AI generation, pixel grading or appearance editing occurs here. Only identical
geometric crop/resize and layout/text are applied to both inputs.
"""
import csv
import hashlib
import json
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

src = Path(os.environ["SIFT_COVER_OUTPUT"])
dest = Path(os.environ["SIFT_COVER_PUBLIC"])
dest.mkdir(parents=True, exist_ok=True)
rows = list(csv.reader((src / "results.csv").read_text().splitlines()))
valid = []
for row in rows:
    name, w, h, profile, fallback, passed, delta, runtime = row
    if fallback == "false" and passed == "true" and float(delta) >= 2.5:
        valid.append(dict(name=name, profile=profile, delta=float(delta), ms=int(runtime)))
if not valid:
    raise SystemExit("No Sift grading passed quality gates with meaningful visual change; do not fake a comparison")

# A scenic real photograph is the clearest portfolio illustration.
priority = {"forest": 4, "lake": 3, "rocket": 2, "astronaut": 1}
valid.sort(key=lambda v: (priority.get(v["name"], 0), v["delta"]), reverse=True)
chosen = valid[0]
before = src / (chosen["name"] + "-before.png")
after = src / (chosen["name"] + "-after.jpg")
source_links = {
    "forest": "https://images.unsplash.com/photo-1441974231531-c6227db76b6e",
    "lake": "https://images.unsplash.com/photo-1501785888041-af3ef285b470",
    "rocket": "https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/data/rocket.jpg",
    "astronaut": "https://github.com/scikit-image/scikit-image/blob/v0.25.2/skimage/data/astronaut.png",
}
original = Image.open(before).convert("RGB")
graded = Image.open(after).convert("RGB")
if original.size != graded.size:
    raise SystemExit("Source/Sift export geometry mismatch")

font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
for width, height, quality, filename in [
    (800, 500, 62, "sift-800.webp"),
    (1600, 1000, 78, "sift-1600.webp"),
]:
    # Identical fit parameters on both inputs, so the seam is a genuine pixel comparison.
    o = ImageOps.fit(original, (width, height), method=Image.Resampling.LANCZOS)
    g = ImageOps.fit(graded, (width, height), method=Image.Resampling.LANCZOS)
    wipe = width // 2
    image = Image.new("RGB", (width, height))
    image.paste(o.crop((0, 0, wipe, height)), (0, 0))
    image.paste(g.crop((wipe, 0, width, height)), (wipe, 0))
    overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    scale = width / 800
    pad, tag_height = int(18 * scale), int(36 * scale)
    size = int(14 * scale)
    font = ImageFont.truetype(font_path, size)
    draw.rounded_rectangle((pad, pad, pad + int(112 * scale), pad + tag_height), radius=int(6 * scale), fill=(6, 12, 17, 224))
    draw.text((pad + int(13 * scale), pad + int(9 * scale)), "ORIGINAL", font=font, fill=(233, 239, 242, 255))
    x = wipe + pad
    draw.rounded_rectangle((x, pad, x + int(145 * scale), pad + tag_height), radius=int(6 * scale), fill=(6, 32, 26, 231))
    draw.text((x + int(12 * scale), pad + int(9 * scale)), "SIFT GRADED", font=font, fill=(162, 242, 217, 255))
    draw.line((wipe, 0, wipe, height), fill=(237, 255, 249, 235), width=max(2, int(2 * scale)))
    rad = int(20 * scale)
    cy = height // 2
    draw.ellipse((wipe - rad, cy - rad, wipe + rad, cy + rad), fill=(8, 20, 22, 232), outline=(214, 255, 245, 238), width=max(2, int(2 * scale)))
    font_arrows = ImageFont.truetype(font_path, int(13 * scale))
    draw.text((wipe - int(11 * scale), cy - int(10 * scale)), "< >", font=font_arrows, fill=(255, 255, 255, 255))
    image = Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")
    target = dest / filename
    image.save(target, "WEBP", quality=quality, method=6)
    print(f"COVER {target} {target.stat().st_size} bytes")

# Publish separate authentic before and after for reproducibility.
original.save(dest / "before.png")
(dest / "after.jpg").write_bytes(after.read_bytes())
def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()
data = {
    "generator": "Sift production core/imaging Pipeline.process",
    "sample": chosen["name"],
    "profile": chosen["profile"],
    "qualityGates": "passed, no fallback",
    "rgbMeanAbsoluteDifference": chosen["delta"],
    "siftProcessingMs": chosen["ms"],
    "sourcePhoto": source_links[chosen["name"]],
    "beforeSha256": sha256(dest / "before.png"),
    "siftJpegSha256": sha256(dest / "after.jpg"),
    "geometry": list(original.size),
    "coverOperation": "Identical geometric crop and resize; left half original, right half unaltered Sift JPEG pixels. Labels and wipe only.",
}
(dest / "provenance.json").write_text(json.dumps(data, indent=2) + "\n")
print("PUBLISHED_EVIDENCE " + json.dumps(data))
