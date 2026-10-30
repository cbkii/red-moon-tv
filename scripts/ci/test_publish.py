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
                    package='com.cbkii.redmoontv.testing', build_run_id='123', signer_sha256='c'*64, apk_sha256=hashlib.sha256(b'apk').hexdigest())
        (root/f'red-moon-tv-TESTING-{suffix}.apk').write_bytes(b'apk')
        (root/f'BUILD_INFO-{suffix}.json').write_text(json.dumps(info))
        (root/f'SIGNER-{suffix}.txt').write_text('Signer #1 certificate SHA-256 digest: '+'c'*64)
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
            with patch('publish.history', return_value=[]) as history, patch('publish.gh', return_value=json.dumps([[release]])) as gh, patch('publish.api', return_value=release) as api, contextlib.redirect_stdout(io.StringIO()):
                publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                self.assertEqual(gh.call_count, 1) # No upload, deletion or tag mutation on rerun.
                writes=[c for c in api.call_args_list if len(c.args)>1 and c.args[1]=='PATCH']
                self.assertTrue(writes[0].args[2]['draft'])
                # A final draft PATCH may replace every temporary download URL.
                # Notes must still identify both files without embedding those URLs.
                notes = writes[0].args[2]['body']
                self.assertIn('download the named files from Assets below', notes)
                for asset in assets:
                    self.assertNotIn(asset['browser_download_url'], notes)
                    if asset['name'].startswith(('BUILD_INFO-', 'red-moon-tv-')):
                        self.assertIn(asset['name'], notes)
    def test_existing_mismatched_asset_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); self.bundle(root)
            assets=[dict(id=i, name=p.name, digest='sha256:wrong') for i,p in enumerate(root.iterdir())]
            release=dict(id=1, tag_name='000-testing-only', draft=True, body=MARKER, assets=assets)
            with patch('publish.history', return_value=[]), patch('publish.gh', return_value=json.dumps([[release]])) as gh, patch('publish.api', return_value=release):
                with self.assertRaises(ValueError): publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                self.assertEqual(gh.call_count, 1)
    def test_signer_change_is_rejected_before_mutating_remote(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.bundle(root)
            prior = dict(channel='testing', signer_sha256='d'*64)
            with patch('publish.gh', return_value='[[]]'), patch('publish.history', return_value=[(dict(draft=True), prior)]), patch('publish.api') as api:
                with self.assertRaises(ValueError): publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                api.assert_not_called()
    def test_unexpected_extra_file_is_rejected_even_with_valid_apk(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.bundle(root); (root/'extra.txt').write_text('extra')
            with patch('publish.gh') as gh:
                with self.assertRaises(ValueError): publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                gh.assert_not_called()

    def test_placeholder_managed_draft_is_repaired_without_upload_or_resign(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.bundle(root)
            assets = [dict(id=i, name=p.name, digest='sha256:'+hashlib.sha256(p.read_bytes()).hexdigest(), created_at='2026-10-30T00:00:00Z', browser_download_url='https://example.invalid/'+p.name) for i,p in enumerate(root.iterdir())]
            release = dict(id=1, tag_name='untagged-placeholder', draft=True, body=MARKER, assets=assets, html_url='https://github.com/cbkii/red-moon-tv/releases')
            def api(path, method='GET', data=None):
                if method == 'PATCH':
                    # Model the observed API behaviour: omitted tag identity becomes a placeholder.
                    return dict(release, **dict(data, tag_name=data.get('tag_name', 'untagged-placeholder')))
                self.assertEqual(method, 'GET')
                return release
            with patch('publish.history', return_value=[]), patch('publish.gh', return_value=json.dumps([[release]])) as gh, patch('publish.api', side_effect=api) as remote, contextlib.redirect_stdout(io.StringIO()):
                publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                self.assertEqual(gh.call_count, 1)
                writes = [c for c in remote.call_args_list if len(c.args)>1 and c.args[1]=='PATCH']
                self.assertEqual(writes[0].args[2]['tag_name'], '000-testing-only')
                self.assertEqual(writes[0].args[2]['target_commitish'], 'a'*40)

    def test_placeholder_identity_is_repaired_before_new_asset_upload(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.bundle(root)
            release = dict(id=1, tag_name='untagged-placeholder', draft=True, body=MARKER, assets=[], html_url='https://github.com/cbkii/red-moon-tv/releases')
            def api(path, method='GET', data=None):
                if method == 'PATCH': release.update(data)
                return release
            def gh(*args, **kwargs):
                if args[0] == 'api': return json.dumps([[release]])
                self.assertEqual(args[:3], ('release', 'upload', '000-testing-only'))
                self.assertEqual(release['tag_name'], '000-testing-only')
                file = Path(args[3])
                release['assets'].append(dict(id=len(release['assets'])+1, name=file.name,
                    digest='sha256:'+hashlib.sha256(file.read_bytes()).hexdigest(),
                    created_at='2026-10-30T00:00:00Z', browser_download_url='https://example.invalid/'+file.name))
                return ''
            with patch('publish.history', return_value=[]), patch('publish.gh', side_effect=gh), patch('publish.api', side_effect=api), contextlib.redirect_stdout(io.StringIO()):
                publisher.publish(root, 'cbkii/red-moon-tv', 'a'*40, 'draft', '')
                self.assertEqual(len(release['assets']), 4)
                self.assertTrue(release['draft'])

    def test_partial_upload_does_not_displace_a_complete_retained_snapshot(self):
        assets = []
        for run in (1, 2, 3):
            key = 'aaaaaaaaaaaa-'+str(run)+'-1'
            names = [f'BUILD_INFO-{key}.json', f'red-moon-tv-TESTING-{key}.apk', f'SIGNER-{key}.txt', f'SHA256SUMS-{key}']
            if run == 3: names = names[:1]  # Interrupted after metadata upload.
            assets.extend(dict(name=name, created_at=f'2026-10-30T00:00:0{run}Z') for name in names)
        retained = publisher.complete_testing_groups(assets)[:2]
        self.assertEqual([x['name'] for x in retained], ['BUILD_INFO-aaaaaaaaaaaa-2-1.json','BUILD_INFO-aaaaaaaaaaaa-1-1.json'])
