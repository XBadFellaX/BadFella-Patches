import json
import unittest
from unittest.mock import patch

import app_updates as monitor


def listing(version, url='https://example.com/app'):
    return {'version': version, 'url': url}


class UpdateTests(unittest.TestCase):
    def test_catalog_deduplicates_and_prefers_reviewed_version(self):
        app = {'packageName': 'app', 'name': 'App', 'targets': [{'version': '1.0'}, {'version': None}]}
        apps = monitor.catalog_apps({'patches': [{'compatiblePackages': [app, app]}]}, 'Private', {'app': '1.1'})
        self.assertEqual(apps, {'app': {'name': 'App', 'catalog': 'Private', 'supported': '1.1'}})

    def test_apkmirror_selects_highest_version_before_publication_time(self):
        feed = b"""<rss><channel><item><title>App 2.0 beta by Author</title>
<pubDate>Thu, 19 Nov 2026 18:11:38 +0000</pubDate>
<link>https://www.apkmirror.com/apk/example/app/beta/</link></item>
<item><title>App 2.0 by Author</title><pubDate>Wed, 18 Nov 2026 18:11:38 +0000</pubDate>
<link>https://www.apkmirror.com/apk/example/app/stable/</link></item></channel></rss>"""
        self.assertEqual(monitor.mirror_latest(feed, 'example/app')['version'], '2.0')
        with self.assertRaises(ValueError):
            monitor.mirror_latest(feed, 'other/app')
        self.assertGreater(monitor.version_key('2.1 beta2'), monitor.version_key('2.1 beta1'))

    @patch.object(monitor, 'request')
    def test_apkmirror_search_verifies_package_and_rejects_ambiguity(self, request):
        request.side_effect = [b'<a href="/apk/team/app/"><a href="/apk/team/watch/">',
                               b'<a href="https://play.google.com/store/apps/details?id=example.app">',
                               b'<a href="https://play.google.com/store/apps/details?id=example.app">']
        self.assertEqual(monitor.mirror_listing('example.app', None), {'status': 'Ambiguous listing'})

    @patch.object(monitor, 'request')
    def test_apkpure_maps_only_requested_packages(self, request):
        request.return_value = b'{"retcode":0,"app_update_response":[{"package_name":"app","version_name":"2.0"}]}'
        self.assertEqual(monitor.pure_latest(['app']), {'app': listing('2.0', 'https://apkpure.com/p/app')})
        self.assertEqual(monitor.pure_latest(['other']), {})

    def test_google_play_handles_numeric_opaque_and_missing_versions(self):
        for version in ['2.10.3', 'a1b2c3', 'Varies with device']:
            row = [None] * 141
            row[140] = [[[version]]]
            raw = "AF_initDataCallback({key: 'ds:5', data:" + json.dumps([None, [None, None, row]]) + '});'
            expected = ({'status': 'Version unavailable'} if version == 'Varies with device'
                        else listing(version, 'https://play.google.com/store/apps/details?id=example.app'))
            self.assertEqual(monitor.play_latest(raw.encode(), 'example.app'), expected)
        with self.assertRaises(ValueError):
            monitor.play_latest(b'<html>Consent required</html>', 'example.app')

    @patch.object(monitor, 'play_listing', return_value=listing('2.1'))
    @patch.object(monitor, 'mirror_listing', side_effect=OSError)
    @patch.object(monitor, 'pure_latest', return_value={'app': listing('2.0')})
    def test_every_source_is_checked_even_if_one_fails(self, pure, mirror, play):
        self.assertEqual(monitor.collect({'app': {}}, {'app': 'example/app'}),
                         {'app': {'APKMirror': {'status': 'Check failed'}, 'APKPure': listing('2.0'),
                                  'Google Play': listing('2.1')}})
        pure.assert_called_once_with(['app'])
        mirror.assert_called_once_with('app', 'example/app')
        play.assert_called_once_with('app')

    def test_comparison_selects_single_link_and_marks_ties(self):
        sources = dict.fromkeys(monitor.SOURCES, listing('2.0'))
        result = monitor.compare('app', sources, '1.0')
        self.assertEqual(result, ('APKMirror', list(monitor.SOURCES), 'Newer than worked on', 0))
        sources['Google Play'] = listing('2.1')
        self.assertEqual(monitor.compare('app', sources, '1.0')[0], 'Google Play')

    def test_comparison_flags_linkedin_and_opaque_disagreement(self):
        sources = {'APKMirror': listing('4.1.1279 beta'), 'APKPure': listing('6.1.1'), 'Google Play': listing('4.1.1276')}
        self.assertIn('version line differs', monitor.compare('com.linkedin.android', sources, '4.1.1276')[2])
        sources['APKPure'] = listing('abc123')
        self.assertIn('version formats differ', monitor.compare('app', sources, '1.0')[2])

    def test_dashboard_uses_worked_on_version_and_only_links_winner(self):
        apps = {'app': {'name': 'App', 'supported': '1.0'}, 'app2': {'name': 'Equal', 'supported': '1.1'}}
        sources = {'APKMirror': listing('2.1'), 'APKPure': listing('2.0'), 'Google Play': {'status': 'Version unavailable'}}
        equal = dict.fromkeys(monitor.SOURCES, listing('1.1'))
        body = monitor.dashboard(apps, {'app': sources, 'app2': equal}, 'Public')
        self.assertIn('| **App** | 1.0 | **2.1** | 2.0 | Version unavailable | **[APKMirror · 2.1]', body)
        self.assertLess(body.index('Newer builds to review'), body.index('Other source comparisons'))
        self.assertEqual(body.count('https://example.com/app'), 2)

    @patch.object(monitor, 'api')
    @patch.object(monitor, 'send_email')
    def test_issue_edit_queues_email_and_unchanged_run_is_silent_after_delivery(self, send, api):
        path = '/issues/12'
        previous = {'body': 'old', 'html_url': 'https://github.example/issues/12'}
        monitor.update_issue(path, previous, 'new', 'Public', '')
        queued = api.call_args.args[1]['body']
        self.assertTrue(queued.endswith(monitor.EMAIL_PENDING))
        self.assertEqual(api.call_args.args[::2], (path, 'PATCH'))
        send.assert_not_called()
        api.reset_mock()
        monitor.update_issue(path, {**previous, 'body': queued}, 'new', 'Public', '{}')
        send.assert_called_once_with({}, previous['html_url'], 'Public')
        api.assert_called_once_with(path, {'body': 'new'}, 'PATCH')
        api.reset_mock()
        send.reset_mock()
        monitor.update_issue(path, {**previous, 'body': 'new'}, 'new', 'Public', '{}')
        api.assert_not_called()
        send.assert_not_called()

    @patch.object(monitor, 'api')
    @patch.object(monitor, 'send_email', side_effect=OSError)
    def test_failed_email_keeps_pending_marker(self, send, api):
        previous = {'body': 'old', 'html_url': 'https://github.example/issues/12'}
        with self.assertRaisesRegex(SystemExit, 'will retry'):
            monitor.update_issue('/issues/12', previous, 'new', 'Public', '{}')
        api.assert_called_once_with('/issues/12', {'body': 'new\n' + monitor.EMAIL_PENDING}, 'PATCH')

    @patch.object(monitor.smtplib, 'SMTP')
    def test_email_uses_tls_and_sends_only_dashboard_link(self, smtp):
        config = {'host': 'mail.example.com', 'username': 'sender@example.com',
                  'password': 'fake', 'to': 'owner@example.com'}
        monitor.send_email(config, 'https://github.example/issues/12', 'Private')
        session = smtp.return_value.__enter__.return_value
        session.starttls.assert_called_once()
        session.login.assert_called_once_with(config['username'], 'fake')
        message = session.send_message.call_args.args[0]
        self.assertEqual(message['To'], config['to'])
        self.assertIn('https://github.example/issues/12', message.get_content())

    @patch.dict(monitor.os.environ, {'APP_UPDATE_ISSUE': '1'})
    @patch('sys.argv', ['app_updates.py', '--catalog-name', 'Private'])
    @patch.object(monitor.Path, 'read_text', return_value='{"patches":[{"compatiblePackages":[{"packageName":"app","name":"App","targets":[]}]}]}')
    @patch.object(monitor, 'api', return_value={'private': False})
    @patch.object(monitor, 'collect')
    def test_private_catalog_cannot_publish_in_public_repository(self, collect, api, read_text):
        with self.assertRaisesRegex(ValueError, 'visibility must match'):
            monitor.main()
        collect.assert_not_called()


if __name__ == '__main__':
    unittest.main()
