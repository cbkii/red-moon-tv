import unittest
from envelope import validate, group, TEST_CODE, TEST_NAME

class EnvelopeTests(unittest.TestCase):
    def info(self):
        return dict(schema=1, source_sha='a' * 40, channel='testing', version_code=TEST_CODE,
                    version_name=TEST_NAME, package='com.cbkii.redmoontv.testing')
    def test_testing_identity(self):
        self.assertEqual(validate(self.info())['version_code'], 999999)
    def test_reject_wrong_source(self):
        for sha in ['master', 'a' * 39, 'A' * 40, '../path']:
            with self.assertRaises(ValueError): validate(dict(self.info(), source_sha=sha))
    def test_reject_wrong_package(self):
        with self.assertRaises(ValueError): validate(dict(self.info(), package='com.jmstudios.redmoon'))
    def test_reject_changed_testing_version(self):
        with self.assertRaises(ValueError): validate(dict(self.info(), version_code=40))
    def test_groups_do_not_collide_across_runs(self):
        self.assertNotEqual(group(self.info(), 1), group(self.info(), 2))
    def test_reject_shell_or_path_version(self):
        for name in ['../bad', 'v1\nname', '$(id)', '1.0;id']:
            with self.assertRaises(ValueError): validate(dict(self.info(), version_name=name))
    def test_release_uses_stable_identity(self):
        self.assertEqual(validate(dict(self.info(), channel='release', package='com.cbkii.redmoontv', version_code=40, version_name='4.0.1-tv.1'))['version_code'], 40)
    def test_reject_invalid_run(self):
        with self.assertRaises(ValueError): group(self.info(), '../1')

from qualify import check_badging, backend_protocol

class ApkMetadataTests(unittest.TestCase):
    def info(self):
        return dict(package='com.cbkii.redmoontv.testing', version_code=999999, version_name='0.0.0-testing')
    def test_android_nine_compatible_testing_apk(self):
        check_badging("package: name='com.cbkii.redmoontv.testing' versionCode='999999' versionName='0.0.0-testing'\nsdkVersion:'26'\n", self.info())
    def test_wrong_package_or_minimum_is_rejected(self):
        for text in ["package: name='evil' versionCode='999999' versionName='0.0.0-testing'\nsdkVersion:'26'", "package: name='com.cbkii.redmoontv.testing' versionCode='999999' versionName='0.0.0-testing'\nsdkVersion:'29'"]:
            with self.assertRaises(ValueError): check_badging(text, self.info())
    def test_backend_metadata_describes_embedded_apk_not_signer_script_revision(self):
        self.assertEqual('apk-jni-v3/root-pipe-v1', backend_protocol(b'dex\0apk-jni-v3/root-pipe-v1\0other'))
        self.assertEqual('apk-jni-v2', backend_protocol(b'dex\0apk-jni-v2\0other'))
    def test_missing_unknown_or_mixed_backend_is_not_silently_labelled(self):
        for dex in [b'unknown', b'apk-jni-v3/root-pipe-v10\0', b'apk-jni-v2\0apk-jni-v3/root-pipe-v1\0']:
            with self.assertRaises(ValueError): backend_protocol(dex)
