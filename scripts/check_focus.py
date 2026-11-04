#!/usr/bin/env python3
"""Build check: focus growth and clipping live in FocusFx.kt only (see the comment there).

One-off scaling or clip changes are how highlighted items kept getting cut off, so they fail CI.
"""
import glob
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'java', 'com', 'osama', 'xtreamtv')
ALLOWED = {'FocusFx.kt'}
# Not focus growth: the player's seek bar and thumb.
ALLOWED_LINES = {'VodControls.kt'}
PATTERN = re.compile(r'\bclipChildren\s*=|\bclipToPadding\s*=|\.scaleX\(|\.scaleY\(')

problems = []
for path in sorted(glob.glob(os.path.join(ROOT, '*.kt'))):
    name = os.path.basename(path)
    if name in ALLOWED or name in ALLOWED_LINES:
        continue
    with open(path, encoding='utf-8') as f:
        for number, line in enumerate(f, 1):
            if PATTERN.search(line):
                problems.append(f'{name}:{number}: {line.strip()}')
if problems:
    print('Use FocusFx / FocusBox instead of these:')
    print('\n'.join(problems))
    sys.exit(1)
print('Focus check passed.')
