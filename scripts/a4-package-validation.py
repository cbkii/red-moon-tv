#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package source-bound ADB scripts/plans and the optional inactive Magisk supervisor."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import zipfile


def package(root, output):
    source = subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    if subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip():
        raise RuntimeError('Commit all source changes before creating a source-bound deliverable')
    output.mkdir(parents=True,exist_ok=True)
    module = output / ('red-moon-tv-supervisor-' + source[:12] + '.zip')
    with zipfile.ZipFile(module,'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted((root/'extras/a4-supervisor').iterdir()):
            if p.is_file():
                info=zipfile.ZipInfo(p.name); info.external_attr=(0o100755 if p.suffix=='.sh' else 0o100644)<<16
                z.writestr(info,p.read_bytes())
    files=[root/'scripts'/name for name in ('a4-collect.py','a4-validate.py','a4-continuity.py')]
    files += sorted((root/'docs').glob('a4-*-plan.json'))
    files += [root/'docs/A4-STARTUP-CONTINUITY.md',root/'docs/A4-ADB-IMPLEMENTATION.md',module]
    inventory={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    bundle=output/('Red-Moon-TV-A4-Continuity-' + source[:12]+'.zip')
    with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
        for p in files: z.write(p,p.name)
        z.writestr('manifest.json',json.dumps({'schema':1,'source_sha':source,'sha256':inventory,
                    'physical_validation':'unverified','supervisor':'optional, inactive until explicitly selected'},indent=2))
    return bundle,module


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=pathlib.Path,required=True)
    args=p.parse_args(); print('\n'.join(map(str,package(pathlib.Path(__file__).resolve().parents[1],args.output))))
