"""Daily upstream discovery; notification state lives in the issue's bot comments."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

MARKER = '<!-- badfella-app-update-state\n'
USER_AGENT = 'BadFella-App-Updates/1.0'
FEEDS = {
    'com.linkedin.android': 'linkedin/linkedin-linkedin',
    'com.openai.chatgpt': 'openai/chatgpt',
    'com.mb.android': 'emby-media/emby-for-android',
    'com.wellocution.androidapp': 'boldvoice/boldvoice-accent-training',
    'com.wanderlog.android': 'wanderlog/wanderlog-trip-planner-app',
    'tech.simha.androidtvremote': 'simha-software/remote-for-android-tv',
}


def request(url, data=None, headers=None):
    headers = {'User-Agent': USER_AGENT, **(headers or {})}
    if data is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(data).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=30) as response:
        return response.read()


def catalog_apps(catalog, catalog_name='Public', reviewed_versions=None):
    if reviewed_versions is None:
        reviewed_versions = {}
    if not isinstance(reviewed_versions, dict) or any(
            not isinstance(v, str) or not v.strip() for v in reviewed_versions.values()):
        raise ValueError('Reviewed versions must map packages to nonempty version strings')
    apps = {}
    for patch in catalog['patches']:
        for app in patch.get('compatiblePackages') or []:
            targets = [t['version'] for t in app['targets'] if t.get('version')]
            apps[app['packageName']] = {'name': app['name'], 'catalog': catalog_name,
                                       'supported': reviewed_versions.get(app['packageName']) or
                                       (targets[-1] if targets else 'version not recorded')}
    if not apps:
        raise ValueError('Catalog has no apps')
    return apps


def mirror_latest(data, path):
    items = ET.fromstring(data).findall('./channel/item')
    if not items:
        raise ValueError('APKMirror feed has no releases')
    item = max(items, key=lambda i: parsedate_to_datetime(i.findtext('pubDate')))
    url = item.findtext('link', '')
    if not url.startswith('https://www.apkmirror.com/apk/' + path + '/'):
        raise ValueError('APKMirror returned an unexpected app link')
    return {'order': parsedate_to_datetime(item.findtext('pubDate')).timestamp(),
            'title': item.findtext('title'), 'url': url, 'source': 'APKMirror'}


def pure_latest(packages):
    device = uuid.uuid4().hex[:16]
    header = {'device_info': {'abis': ['arm64-v8a'], 'android_id': device, 'os_ver': '35',
                            'os_ver_name': '15', 'platform': 1, 'screen_height': 2400, 'screen_width': 1080}}
    payload = {'app_info_for_update': [{'package_name': p, 'version_code': 0, 'is_system': False,
                                       'version_id': '', 'cached_size': -1} for p in packages],
               'android_id': device, 'application_id': 'com.apkpure.aegon', 'cached_size': -1}
    data = json.loads(request('https://tapi.pureapk.com/v3/get_app_update?hl=en-US', payload,
                             {'ual-access-businessid': 'projecta', 'ual-access-projecta': json.dumps(header)}))
    if data.get('retcode') != 0:
        raise ValueError('APKPure refused the update request')
    return {app['package_name']: {'order': int(app['version_code']), 'title': app['version_name'],
                                 'url': 'https://apkpure.com/p/' + app['package_name'], 'source': 'APKPure'}
            for app in data['app_update_response'] if app['package_name'] in packages}


def play_latest(data, package):
    html = data.decode('utf-8')
    match = re.search(r"key:\s*['\"]ds:5['\"].*?data:\s*", html, re.S)
    if not match:
        raise ValueError('Google Play app metadata missing')
    # shortcut: public ds:5 layout from google-play-scraper; review its mapping if Play changes the page.
    details = json.JSONDecoder().raw_decode(html[match.end():])[0]
    version = details[1][2][140][0][0][0]
    if not isinstance(version, str) or not version.strip() or version == 'Varies with device':
        raise ValueError('Google Play did not expose an app version')
    order = [int(part) for part in version.split('.')] if re.fullmatch(r'\d+(?:\.\d+)*', version) else None
    return {'order': order, 'title': version,
            'url': 'https://play.google.com/store/apps/details?id=' + package, 'source': 'Google Play'}


def check_play(packages):
    def check(package):
        try:
            url = 'https://play.google.com/store/apps/details?id=' + package + '&hl=en_US&gl=US'
            return play_latest(request(url), package), None
        except urllib.error.HTTPError as error:
            return None, 'Listing unavailable' if error.code == 404 else f'HTTP {error.code}'
        except (urllib.error.URLError, ValueError, KeyError, TypeError, IndexError, TimeoutError) as error:
            return None, type(error).__name__

    found, errors = {}, {}
    packages = sorted(packages)
    with ThreadPoolExecutor(max_workers=6) as pool:
        for package, (item, error) in zip(packages, pool.map(check, packages)):
            if error is None:
                found[package] = item
            else:
                errors[package] = error
    return found, errors


def collect(apps, play_packages=()):
    play_packages = set(play_packages)
    found, errors = {}, {}
    pure = [p for p in apps if p not in FEEDS and p not in play_packages]
    if pure:
        try:
            found.update(pure_latest(pure))
            for package in pure:
                if package not in found:
                    errors[package] = 'APKPure did not return this app'
        except (urllib.error.URLError, ValueError, KeyError, TypeError, TimeoutError) as error:
            errors.update({p: type(error).__name__ for p in pure})
    for package in apps.keys() & FEEDS.keys():
        try:
            found[package] = mirror_latest(request('https://www.apkmirror.com/apk/' + FEEDS[package] + '/feed/'), FEEDS[package])
        except (urllib.error.URLError, ValueError, KeyError, TypeError, TimeoutError, ET.ParseError) as error:
            errors[package] = type(error).__name__
    play_found, play_errors = check_play(apps.keys() & play_packages)
    found.update(play_found)
    errors.update(play_errors)
    return found, errors


def advance(previous, found, errors):
    latest = dict(previous.get('latest', {}))
    changed = []
    for package, item in found.items():
        old = latest.get(package)
        # Source order is comparable only within one provider; a provider switch establishes a new baseline.
        if old and old['source'] == item['source'] and old['order'] is not None and item['order'] is not None:
            is_new = item['order'] > old['order']
        else:
            is_new = old is None or item['source'] != old['source'] or item['title'] != old['title']
        if is_new:
            latest[package] = item
            changed.append(package)
    state = {'latest': latest, 'errors': errors}
    notify = bool(changed) or errors != previous.get('errors', {}) or not previous
    return state, changed, notify


def update_state(apps, previous, play_packages):
    found, errors = collect(apps, play_packages)
    state, changed, notify = advance(previous, found, errors)
    play_found, play_errors = check_play(apps.keys() - set(play_packages))
    old_play = previous.get('play', {})
    state['play'], play_changed, play_notify = advance(old_play, play_found, play_errors)
    availability_changed = {p for p in old_play.get('errors', {}).keys() | play_errors.keys()
                            if old_play.get('errors', {}).get(p) != play_errors.get(p)}
    changed = (set(changed) | set(play_changed) | availability_changed) & apps.keys()
    return state, changed, notify or play_notify


def text(value):
    return str(value).replace('\n', ' ').replace('|', '\\|').replace('`', '').replace('@', '@\u200b')


def message(apps, previous, state, changed):
    play = state.get('play', {})
    play_errors = play.get('errors', {})
    lines = ['@XBadFellaX', '', 'App update watch: ' + datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC'), '',
             'Starting snapshot. Future alerts report listing changes since this check.' if not previous else 'Listing or source availability changes.', '',
             '| Catalog | App (worked-on version) | Mirror listing | Google Play (US) |', '| --- | --- | --- | --- |']
    for package in sorted(changed):
        item = state['latest'].get(package)
        play_item = play.get('latest', {}).get(package)
        if item and item['source'] == 'Google Play':
            item, play_item = None, item
        mirror_link = f"[{text(item['title'])}]({item['url']}) ({item['source']})" if item else '—'
        play_link = f"[{text(play_item['title'])}]({play_item['url']})" if play_item else '—'
        if package in play_errors:
            play_link = 'Version unavailable'
            if play_errors[package] != 'Listing unavailable':
                play_link = f'[{play_link}](https://play.google.com/store/apps/details?id={package})'
        lines.append(f"| {text(apps[package]['catalog'])} | {text(apps[package]['name'])} ({text(apps[package]['supported'])}) | "
                     f'{mirror_link} | {play_link} |')
    if not changed:
        lines.append('| | No new listings | | |')
    if state['errors']:
        lines += ['', 'Could not check: ' + '; '.join(text(apps[p]['name']) + ' (' + e + ')' for p, e in sorted(state['errors'].items()))]
    elif previous.get('errors'):
        lines += ['', 'Primary sources are responding again.']
    lines += ['', 'Worked-on versions identify reviewed inputs or declared patch targets. Mirror listings can include betas. Google Play availability varies by region, device and rollout.',
              '', MARKER + json.dumps(state, separators=(',', ':')) + '\n-->']
    return '\n'.join(lines)


def api(path, data=None):
    return json.loads(request('https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + path, data,
                              {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'}))


def previous_state(issue):
    page, state = 1, {}
    while True:
        comments = api(f'/issues/{issue}/comments?per_page=100&page={page}')
        for comment in comments:
            if comment['user']['login'] != 'github-actions[bot]' or MARKER not in comment['body']:
                continue
            state = json.loads(comment['body'].split(MARKER, 1)[1].split('\n-->', 1)[0])
        if len(comments) < 100:
            return state
        page += 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true', help='Read upstreams and print a snapshot; never access GitHub issues')
    parser.add_argument('--catalog', type=Path, default=Path('patches-list.json'), help='catalog from the repository being monitored')
    parser.add_argument('--catalog-name', choices=['Public', 'Private'], default='Public')
    parser.add_argument('--reviewed-versions', type=Path, help='recorded app versions used for patch work; display only')
    parser.add_argument('--play-package', action='append', default=[], help='package to check through public Play metadata instead of APKPure')
    args = parser.parse_args()
    reviewed = json.loads(args.reviewed_versions.read_text(encoding='utf-8')) if args.reviewed_versions else {}
    apps = catalog_apps(json.loads(args.catalog.read_text(encoding='utf-8')), args.catalog_name, reviewed)
    issue = os.environ.get('APP_UPDATE_ISSUE')
    if not args.dry_run and (not issue or not issue.isdecimal()):
        raise ValueError('APP_UPDATE_ISSUE must identify the monitoring issue')
    if not args.dry_run and args.catalog_name == 'Private' and not api('')['private']:
        raise ValueError('Update notifications must stay in a private repository')
    previous = {} if args.dry_run else previous_state(issue)
    state, changed, notify = update_state(apps, previous, args.play_package)
    errors = state['errors']
    body = message(apps, previous, state, changed)
    if args.dry_run:
        print(body)
    elif notify:
        api(f'/issues/{issue}/comments', {'body': body})
        print(f"Posted {len(changed)} app(s), {len(errors)} primary source error(s), {len(state['play']['errors'])} additional Play version(s) unavailable")
    else:
        print('No new listings or changes in source availability; no notification sent')
    if errors:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
