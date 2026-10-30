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
    def test_no_reviewer_or_missing_device_identity(self):
        record = copy.deepcopy(self.record); record['device']['api'] = 32
        with self.assertRaises(ValueError): acceptance(record, self.info)
        record = copy.deepcopy(self.record); record['reviewer'] = ''
        with self.assertRaises(ValueError): acceptance(record, self.info)
    def test_attempts_have_distinct_snapshot_names(self):
        info = dict(schema=1, source_sha='a'*40, channel='testing', package='com.cbkii.redmoontv.testing', version_code=999999, version_name='0.0.0-testing')
        self.assertNotEqual(group(info, 123, 1), group(info, 123, 2))
