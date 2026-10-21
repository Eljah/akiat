#!/usr/bin/env python3
"""Static validation of source .ipynb and Python scripts, without installing or running models."""
from __future__ import annotations
import ast, json, os, pathlib, sys
ROOT=pathlib.Path(__file__).resolve().parents[1]
issues=[]
notebooks=list(ROOT.glob('notebooks/*.ipynb'))
for path in notebooks:
    try:
        obj=json.loads(path.read_text(encoding='utf-8'))
        if obj.get('nbformat')!=4 or not isinstance(obj.get('cells'),list):
            issues.append(f'{path}: invalid Jupyter format')
            continue
        for i,cell in enumerate(obj['cells']):
            if cell.get('outputs'):
                issues.append(f'{path}: saved output in cell {i}')
            if cell.get('execution_count') is not None:
                issues.append(f'{path}: executed cell {i}')
            if cell.get('cell_type')=='code':
                src=''.join(cell.get('source',[]))
                # Colab IPython magics (%pip, !curl) are not Python statements.
                src='\n'.join(line for line in src.splitlines() if not line.lstrip().startswith(('%','!')))
                try:
                    ast.parse(src)
                except SyntaxError as exc:
                    issues.append(f'{path}: syntax cell {i}: {exc}')
    except (ValueError,UnicodeError) as exc:
        issues.append(f'{path}: {exc}')
modules=[]
for directory, subdirs, filenames in os.walk(ROOT):
    subdirs[:] = [name for name in subdirs if not name.startswith('.') and name not in {'venv', '__pycache__', 'node_modules'}]
    modules.extend(pathlib.Path(directory)/name for name in filenames if name.endswith('.py'))
for path in modules:
    if 'tools/history/' in path.relative_to(ROOT).as_posix():
        continue
    try: ast.parse(path.read_text(encoding='utf-8'))
    except SyntaxError as exc: issues.append(f'{path}: {exc}')
print(f'Notebooks: {len(notebooks)}; Python modules: {len(modules)}')
if issues:
    print('\n'.join(issues),file=sys.stderr)
    sys.exit(1)
print('PASS: static validation')
