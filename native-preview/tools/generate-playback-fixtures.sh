#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
ffmpeg_bin="${FFMPEG:-ffmpeg}"
assets="$project_dir/app/src/androidTest/assets/playback"
mkdir -p "$assets"
"$ffmpeg_bin" -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=24' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 40 -c:v libx264 -preset veryfast \
  -b:v 800k -maxrate 800k -bufsize 1600k -pix_fmt yuv420p -g 48 -c:a aac -b:a 96k \
  -af volume=0.4 -ac 2 -movflags +faststart "$assets/sample.mp4" -y
"$ffmpeg_bin" -hide_banner -loglevel error -i "$assets/sample.mp4" -c copy \
  -hls_time 4 -hls_list_size 0 -hls_playlist_type vod \
  -hls_segment_filename "$assets/segment%02d.ts" "$assets/sample.m3u8" -y
