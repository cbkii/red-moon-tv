import copy
import unittest
from promote import acceptance, CHECKS
from envelope import group

class PromotionTests(unittest.TestCase):
    def setUp(self):
        self.info = dict(source_sha='a'*40, apk_sha256='b'*64, signer_sha256='c'*64, package='com.cbkii.redmoontv')
        self.record = dict(self.info, schema=1, reviewer='CB', date='2026-10-30',
            device=dict(model='50C350KP', api=28, abi='armeabi-v7a', fingerprint='physical-build'),
            checks={name: dict(status='passed', evidence='timestamped physical transcript') for name in CHECKS})
    def test_exact_signed_candidate_accepted(self):
        self.assertEqual(acceptance(self.record, self.info), self.record)
    def test_testing_or_different_hash_never_qualifies_stable(self):
        for key, value in [('apk_sha256', 'd'*64), ('package', 'com.cbkii.redmoontv.testing'), ('signer_sha256', 'e'*64)]:
            record = copy.deepcopy(self.record); record[key] = value
            with self.assertRaises(ValueError): acceptance(record, self.info)
    def test_unperformed_gate_or_boolean_cannot_publish(self):
        record = copy.deepcopy(self.record); record['checks']['fades'] = dict(status='not run', evidence='')
        with self.assertRaises(ValueError): acceptance(record, self.info)
        with self.assertRaises(ValueError): acceptance(dict(a4_qualified=True), self.info)
    def test_every_requested_viewing_path_and_persistence_is_a_release_gate(self):
        for name in ('manual_override', 'fade_persistence', 'brightness_android', 'warmth_android',
                     'brightness_tuner', 'warmth_tuner', 'brightness_hdmi', 'warmth_hdmi', 'brightness_hdr', 'warmth_hdr'):
            record = copy.deepcopy(self.record); record['checks'].pop(name)
            with self.subTest(gate=name), self.assertRaises(ValueError): acceptance(record, self.info)

    def test_no_reviewer_or_missing_device_identity(self):
        record = copy.deepcopy(self.record); record['device']['api'] = 32
        with self.assertRaises(ValueError): acceptance(record, self.info)
        record = copy.deepcopy(self.record); record['reviewer'] = ''
        with self.assertRaises(ValueError): acceptance(record, self.info)
    def test_attempts_have_distinct_snapshot_names(self):
        info = dict(schema=1, source_sha='a'*40, channel='testing', package='com.cbkii.redmoontv.testing', version_code=999999, version_name='0.0.0-testing')
        self.assertNotEqual(group(info, 123, 1), group(info, 123, 2))

    def test_promotion_preserves_release_identity_and_existing_bytes(self):
        import hashlib
        import json
        from pathlib import Path
        from unittest.mock import patch
        import promote as promotion
        apk = b'existing signed candidate'
        info = dict(self.info, schema=1, channel='release', version_code=40, version_name='4.0.1-tv.1',
                    apk_sha256=hashlib.sha256(apk).hexdigest())
        record = dict(self.record, apk_sha256=info['apk_sha256'])
        files = {
            'red-moon-tv-4.0.1-tv.1.apk': apk,
            'BUILD_INFO-4.0.1-tv.1.json': json.dumps(info).encode(),
            'SIGNER-4.0.1-tv.1.txt': ('Signer #1 certificate SHA-256 digest: '+'c'*64).encode()
        }
        files['SHA256SUMS-4.0.1-tv.1'] = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name,data in files.items()).encode()
        assets = [dict(name=name,digest='sha256:'+hashlib.sha256(data).hexdigest()) for name,data in files.items()]
        release = dict(id=1,draft=True,body=promotion.MARKER,assets=assets,tag_name='v4.0.1-tv.1',
                       target_commitish='a'*40,name='Red Moon TV v4.0.1-tv.1',html_url='https://example.invalid/release')
        def api(path, method='GET', data=None):
            if method == 'PATCH':
                self.assertEqual(data['tag_name'], 'v4.0.1-tv.1')
                self.assertEqual(data['target_commitish'], 'a'*40)
                return dict(release, **dict(data, tag_name=data.get('tag_name', 'untagged-placeholder')))
            if path.endswith('matching-refs/tags/v4.0.1-tv.1'): return []
            if path.endswith('/commits/master'): return dict(sha='a'*40)
            if path == 'repos/cbkii/red-moon-tv': return dict(default_branch='master')
            return copy.deepcopy(release)
        def gh(*args):
            self.assertEqual(args[:2], ('release','download'))
            name = args[args.index('--pattern')+1]
            (Path(args[args.index('--dir')+1])/name).write_bytes(files[name])
            return ''
        def run(args):
            self.assertNotIn('sign', args)
            if str(args[0]).endswith('apksigner'): return 'Signer #1 certificate SHA-256 digest: '+'c'*64
            if str(args[0]).endswith('aapt'): return "package: name='com.cbkii.redmoontv' versionCode='40' versionName='4.0.1-tv.1'\nsdkVersion:'26'\n"
            return ''
        with patch('promote.api',side_effect=api),patch('promote.gh',side_effect=gh),patch('promote.run',side_effect=run),patch('builtins.print'):
            promotion.promote('cbkii/red-moon-tv',1,record,Path('/verification-tools'))
