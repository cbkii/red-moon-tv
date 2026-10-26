#!/usr/bin/env python3
"""Release metadata contract. Standard-library only; never executes asset contents."""
import argparse
import json
import re
from pathlib import Path

TEST_CODE = 999999
TEST_NAME = '0.0.0-testing'
MARKER = '<!-- red-moon-tv-testing-managed -->'


def validate(info):
    if info.get('schema') != 1:
        raise ValueError('Unsupported build metadata schema')
    if not re.fullmatch(r'[0-9a-f]{40}', info.get('source_sha', '')):
        raise ValueError('Expected immutable source SHA')
    if info.get('channel') not in ('testing', 'release'):
        raise ValueError('Unsupported channel')
    if not isinstance(info.get('version_code'), int) or not 40 <= info['version_code'] < 2100000000:
        raise ValueError('Invalid version code')
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z.+-]{0,79}', info.get('version_name', '')):
        raise ValueError('Invalid version name')
    expected = 'com.cbkii.redmoontv' + ('.testing' if info['channel'] == 'testing' else '')
    if info.get('package') != expected:
        raise ValueError('Unexpected package identity')
    if info['channel'] == 'testing' and (info['version_code'], info['version_name']) != (TEST_CODE, TEST_NAME):
        raise ValueError('TESTING has fixed version identity')
    return info


def group(info, run_id):
    validate(info)
    if not re.fullmatch(r'[1-9][0-9]*', str(run_id)):
        raise ValueError('Invalid run ID')
    return info['source_sha'][:12] + '-' + str(run_id)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--sha', required=True)
    parser.add_argument('--channel', choices=('testing', 'release'), required=True)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    info = validate(dict(schema=1, source_sha=args.sha, channel=args.channel,
                         version_name=args.version_name, version_code=args.version_code,
                         package='com.cbkii.redmoontv' + ('.testing' if args.channel == 'testing' else '')))
    args.output.write_text(json.dumps(info, indent=2) + '\n', encoding='utf-8')
