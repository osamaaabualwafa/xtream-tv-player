#!/usr/bin/env bash
# A 10-minute MPEG-TS "live" stream for .ts channels (too big to keep in git).
set -e
cd "$(dirname "$0")/media"
command -v ffmpeg >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg >/dev/null; }
ffmpeg -loglevel error -y -f lavfi -i "testsrc=s=640x360:r=15" -f lavfi -i "sine=frequency=520:sample_rate=22050" -t 600 \
  -vf "format=yuv420p" -c:v libx264 -profile:v baseline -preset veryfast -crf 36 -g 30 \
  -c:a aac -b:a 24k -f mpegts live_long.ts
ls -la live_long.ts
