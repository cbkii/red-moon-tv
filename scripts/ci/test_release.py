import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import publish
from resolve_release import resolve


REPO = 'cbkii/red-moon-tv'
SHA = 'a' * 40


class ReleaseRequestTests(unittest.TestCase):
    def event(self, body='/prerelease v106'):
        return dict(repository=dict(full_name=REPO, default_branch='master'),
                    comment=dict(author_association='OWNER', user=dict(login='cbkii'), body=body))

    def test_maintainer_comment_resolves_default_source_and_numeric_version(self):
        with patch('resolve_release.api', side_effect=[dict(permission='admin'), dict(sha=SHA)]) as api:
            info, outputs = resolve(self.event(), 'issue_comment', REPO, 'master', SHA)
        self.assertEqual(('106', 106, 'com.cbkii.redmoontv'), (info['version_name'], info['version_code'], info['package']))
        self.assertEqual('prerelease', outputs['state'])
        self.assertTrue(api.call_args_list[-1].args[0].endswith('/commits/master'))

    def test_comment_cannot_select_pr_code_or_inject_shell_or_output_lines(self):
        for body in ('/prerelease v106 extra', '/prerelease v106\nsource=evil', '/prerelease v$(id)', '/prerelease v../106'):
            with self.subTest(body=body), patch('resolve_release.api') as api:
                with self.assertRaises(ValueError): resolve(self.event(body), 'issue_comment', REPO, 'master', SHA)
                api.assert_not_called()

    def test_association_is_not_a_substitute_for_current_write_permission(self):
        event = self.event(); event['comment']['author_association'] = 'COLLABORATOR'
        with patch('resolve_release.api', return_value=dict(permission='read')):
            with self.assertRaises(ValueError): resolve(event, 'issue_comment', REPO, 'master', SHA)
        event['comment']['author_association'] = 'NONE'
        with patch('resolve_release.api') as api:
            with self.assertRaises(ValueError): resolve(event, 'issue_comment', REPO, 'master', SHA)
            api.assert_not_called()

    def test_stale_source_other_repository_and_feature_branch_are_rejected(self):
        with patch('resolve_release.api', side_effect=[dict(permission='write'), dict(sha='b' * 40)]):
            with self.assertRaises(ValueError): resolve(self.event(), 'issue_comment', REPO, 'master', SHA)
        for repo, branch in (('other/repo', 'master'), (REPO, 'feature')):
            with patch('resolve_release.api') as api:
                with self.assertRaises(ValueError): resolve(self.event(), 'issue_comment', repo, branch, SHA)
                api.assert_not_called()

    def test_dispatch_separates_draft_prerelease_and_accepted_stable_promotion(self):
        for operation, state in (('prepare', 'draft'), ('prerelease', 'prerelease')):
            event = self.event(); event['inputs'] = dict(operation=operation, version_name='106', version_code='106')
            with patch('resolve_release.api', return_value=dict(sha=SHA)):
                self.assertEqual(state, resolve(event, 'workflow_dispatch', REPO, 'master', SHA)[1]['state'])
        event['inputs']['operation'] = 'promote'
        with self.assertRaises(ValueError): resolve(event, 'workflow_dispatch', REPO, 'master', SHA)


class PrereleasePublicationTests(unittest.TestCase):
    def bundle(self, root, testing=False):
        suffix = 'aaaaaaaaaaaa-123' if testing else '106'
        prefix = 'TESTING-' if testing else ''
        info = dict(schema=1, source_sha=SHA, channel='testing' if testing else 'release',
                    version_name='0.0.0-testing' if testing else '106', version_code=999999 if testing else 106,
                    package='com.cbkii.redmoontv' + ('.testing' if testing else ''),
                    signer_sha256='c' * 64, apk_sha256=hashlib.sha256(b'apk').hexdigest())
        (root / f'red-moon-tv-{prefix}{suffix}.apk').write_bytes(b'apk')
        (root / f'BUILD_INFO-{suffix}.json').write_text(json.dumps(info))
        (root / f'SIGNER-{suffix}.txt').write_text('Signer #1 certificate SHA-256 digest: ' + 'c' * 64)
        (root / f'SHA256SUMS-{suffix}').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(root.iterdir())))

    def test_verified_uploads_publish_only_as_prerelease_without_latest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.bundle(root)
            release = dict(id=1, draft=True, prerelease=False, tag_name='v106', target_commitish=SHA,
                           body='<!-- red-moon-tv-release-managed -->', assets=[], html_url='https://github.com/cbkii/red-moon-tv/releases/tag/v106')
            def api(path, method='GET', data=None):
                if path == f'repos/{REPO}': return dict(default_branch='master')
                if path.endswith('/commits/master'): return dict(sha=SHA)
                if 'matching-refs' in path: return []
                if method in ('POST', 'PATCH'): release.update(data)
                return release
            def gh(*args, **kwargs):
                if args[0] == 'api': return '[[]]'
                self.assertEqual(('release', 'upload', 'v106'), args[:3])
                p = Path(args[3])
                release['assets'].append(dict(name=p.name, digest='sha256:' + hashlib.sha256(p.read_bytes()).hexdigest()))
                return ''
            with patch('publish.api', side_effect=api) as remote, patch('publish.gh', side_effect=gh), contextlib.redirect_stdout(io.StringIO()):
                publish.publish(root, REPO, SHA, 'prerelease', 'v106')
            self.assertEqual(4, len(release['assets']))
            self.assertFalse(release['draft']); self.assertTrue(release['prerelease'])
            self.assertEqual('false', release['make_latest'])
            self.assertIn('has not completed physical A4 acceptance', release['body'])
            self.assertIn('APK SHA-256:', release['body'])

    def test_testing_stable_and_stale_source_cannot_use_prerelease_path(self):
        for testing, state in ((True, 'prerelease'), (False, 'stable')):
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp); self.bundle(root, testing)
                with patch('publish.api') as api, patch('publish.gh') as gh:
                    with self.assertRaises(ValueError): publish.publish(root, REPO, SHA, state, 'v106')
                    api.assert_not_called(); gh.assert_not_called()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.bundle(root)
            with patch('publish.api', side_effect=[dict(default_branch='master'), dict(sha='b' * 40)]), patch('publish.gh') as gh:
                with self.assertRaises(ValueError): publish.publish(root, REPO, SHA, 'prerelease', 'v106')
                gh.assert_not_called()
