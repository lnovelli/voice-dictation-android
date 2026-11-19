#!/usr/bin/env python3
"""Convert encoder calibration from ONNX NCT to QNN native NTC layout."""
import argparse
from pathlib import Path
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('--src', type=Path, required=True, help='export/calibration directory')
p.add_argument('--out', type=Path, required=True, help='conversion working directory')
p.add_argument('--frames', type=int, default=1000)
a = p.parse_args()
a.src, a.out = a.src.resolve(), a.out.resolve()
(a.out / 'calib').mkdir(parents=True, exist_ok=True)
paths = []
for line in (a.src / 'encoder.txt').read_text().splitlines():
    source = Path(line.strip())
    x = np.fromfile(source, np.float32).reshape(1, 128, a.frames)
    dest = a.out / 'calib' / source.name
    np.ascontiguousarray(x.transpose(0, 2, 1)).tofile(dest)
    assert np.array_equal(np.fromfile(dest, np.float32).reshape(1, a.frames, 128).transpose(0, 2, 1), x)
    paths.append(str(dest))
assert len(paths) == 18, len(paths)
(a.out / 'encoder.txt').write_text('\n'.join(paths) + '\n')
print('native x [1,frames,128], round-trip verified:', len(paths))
