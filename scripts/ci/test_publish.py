import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import publish as publisher
from envelope import MARKER
from resolve_testing import resolve


class ResolverTests(unittest.TestCase):
    def test_untrusted_comment_never_reads_or_builds_a_source(self):
        event = dict(issue=dict(pull_request={}, number=1), comment=dict(author_association='NONE', body='/testing-apk'))
        with patch('resolve_testing.api') as api:
            self.assertIsNone(resolve(event, 'issue_comment', 'cbkii/red-moon-tv', 'master'))
            api.assert_not_called()
    def test_signed_fork_source_is_rejected(self):
        event = dict(repository=dict(default_branch='master'), inputs=dict(source_to_build='#1'))
        with patch('resolve_testing.api', return_value=dict(head=dict(repo=dict(full_name='other/repo')))):
            with self.assertRaises(ValueError): resolve(event, 'workflow_dispatch', 'cbkii/red-moon-tv', 'master')
    def test_dispatch_from_nondefault_workflow_is_rejected(self):
        with self.assertRaises(ValueError): resolve(dict(repository=dict(default_branch='master')), 'workflow_dispatch', 'cbkii/red-moon-tv', 'branch')
    def test_same_repo_pr_resolves_immutable_head(self):
        event = dict(repository=dict(default_branch='master'), inputs=dict(source_to_build='1'))
        with patch('resolve_testing.api', return_value=dict(head=dict(repo=dict(full_name='cbkii/red-moon-tv'), sha='a'*40, ref='feature'))):
            self.assertEqual(resolve(event, 'workflow_dispatch', 'cbkii/red-moon-tv', 'master')['source_sha'], 'a'*40)


class PublisherTests(unittest.TestCase):
    def bundle(self, root):
        suffix = 'aaaaaaaaaaaa-123'
        info = dict(schema=1, source_sha='a'*40, channel='testing', version_name='0.0.0-testing', version_code=999999,
                    package='com.cbkii.redmoontv.testing', build_run_id='123', apk_sha256=hashlib.sha256(b'apk').hexdigest())
        (root/f'red-moon-tv-TESTING-{suffix}.apk').write_bytes(b'apk')
        (root/f'BUILD_INFO-{suffix}.json').write_text(json.dumps(info))
        (root/f'SIGNER-{suffix}.txt').write_text('verified signer')
        (root/f'SHA256SUMS-{suffix}').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(root.iterdir())))
        return info
    def test_published_release_is_never_modified(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.bundle(root)
            with patch('publish.gh', return_value=json.dumps([[dict(tag_name='000-testing-only', draft=False, body=MARKER)]])), patch('publish.api') as api:
                with self.assertRaises(ValueError): publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                api.assert_not_called()
    def test_tampered_apk_is_rejected_before_github(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.bundle(root)
            next(root.glob('*.apk')).write_bytes(b'tampered')
            with patch('publish.gh') as gh:
                with self.assertRaises(ValueError): publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                gh.assert_not_called()
    def test_complete_uploaded_snapshot_is_idempotent_and_remains_draft(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.bundle(root)
            assets=[dict(id=i, name=p.name, digest='sha256:'+hashlib.sha256(p.read_bytes()).hexdigest(), created_at='2026-10-26T00:00:00Z', browser_download_url='https://example.invalid/'+p.name) for i,p in enumerate(root.iterdir())]
            release=dict(id=1, tag_name='000-testing-only', draft=True, body=MARKER, assets=assets, html_url='https://github.com/cbkii/red-moon-tv/releases')
            with patch('publish.gh', return_value=json.dumps([[release]])) as gh, patch('publish.api', return_value=release) as api, contextlib.redirect_stdout(io.StringIO()):
                publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                self.assertEqual(gh.call_count, 1) # No upload, deletion or tag mutation on rerun.
                writes=[c for c in api.call_args_list if len(c.args)>1 and c.args[1]=='PATCH']
                self.assertTrue(writes[0].args[2]['draft'])
    def test_existing_mismatched_asset_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.bundle(root)
            assets=[dict(id=i, name=p.name, digest='sha256:wrong') for i,p in enumerate(root.iterdir())]
            release=dict(id=1, tag_name='000-testing-only', draft=True, body=MARKER, assets=assets)
            with patch('publish.gh', return_value=json.dumps([[release]])) as gh, patch('publish.api', return_value=release):
                with self.assertRaises(ValueError): publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                self.assertEqual(gh.call_count, 1)
