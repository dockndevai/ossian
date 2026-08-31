#!/usr/bin/env bash
# Turns the frame sequence from record.mjs into the two artefacts the README uses.
#
# Both, because they are for different places. GitHub renders a GIF inline from a repo path and
# will not render an MP4 from one — but the MP4 is a third the size at better quality, so it is
# worth having for anywhere that can play it.
set -euo pipefail
FRAMES="${1:-frames}"
OUT="${2:-.}"

# 12.5 fps against a 5 fps capture: two and a half times real speed. The walkthrough takes about
# eighty seconds to perform and nobody watches eighty seconds of a README.
ffmpeg -y -framerate 12.5 -i "$FRAMES/f%05d.png" -vf "scale=1280:-2:flags=lanczos" \
  -c:v libx264 -pix_fmt yuv420p -crf 23 -movflags +faststart "$OUT/ossian-demo.mp4"

# One palette for the whole clip. Per-frame palettes make flat UI backgrounds shimmer as the
# colours are re-chosen each frame, which on a mostly-static page is the only motion you notice.
ffmpeg -y -framerate 12.5 -i "$FRAMES/f%05d.png" \
  -vf "scale=1000:-1:flags=lanczos,palettegen=max_colors=128:stats_mode=diff" "$OUT/palette.png"
ffmpeg -y -framerate 12.5 -i "$FRAMES/f%05d.png" -i "$OUT/palette.png" \
  -lavfi "scale=1000:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
  "$OUT/ossian-demo.gif"
rm -f "$OUT/palette.png"
