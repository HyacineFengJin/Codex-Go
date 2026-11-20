#!/usr/bin/env python3
"""Remove generated project caches while preserving source and the current deliverable."""
import shutil
from pathlib import Path

root = Path(__file__).resolve().parent.parent
assert (root / 'app/build.gradle').is_file() and (root / 'gradlew').is_file()
targets = [root / name for name in ['.gradle', '.kotlin', 'build', 'app/build', 'codex-runtime/build']]
targets += list((root / 'examples').glob('*/build'))
targets += [p for p in root.rglob('__pycache__') if '.git' not in p.relative_to(root).parts]
for path in targets:
    assert path.parent.resolve().is_relative_to(root), f'Cache parent escaped the project: {path}'
    if path.is_symlink():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)
    elif path.exists():
        path.unlink()
print('Project build/test caches cleared. Source, toolchain locks and artifacts/codex-go.apk retained.')
