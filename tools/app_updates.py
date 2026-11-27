"""Compare app versions and keep the monitoring issue's dashboard current."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from email.message import EmailMessage
from email.utils import parsedate_to_datetime
from functools import partial
import json
import os
from pathlib import Path
import re
import smtplib
import ssl
from threading import Lock
from time import monotonic, sleep
import urllib.error
from urllib.parse import quote
import urllib.request
import uuid
import xml.etree.ElementTree as ET

MARKER = '<!-- badfella-app-update-dashboard -->'
EMAIL_PENDING = '<!-- badfella-app-update-email-pending -->'
USER_AGENT = 'BadFella-App-Updates/1.0'
SOURCES = ('APKMirror', 'APKPure', 'Google Play')
SOURCE_ERRORS = (OSError, ValueError, KeyError, TypeError, IndexError, ET.ParseError)
MIRROR_SEARCH_LOCK = Lock()
next_mirror_search = 0.0
FEEDS = {
    'com.linkedin.android': 'linkedin/linkedin-linkedin',
    'com.openai.chatgpt': 'openai/chatgpt',
    'com.mb.android': 'emby-media/emby-for-android',
    'com.ticktick.task': 'ticktick-limited/ticktick-to-do-list-with-reminder-day-planner',
    'com.mixapplications.ultimateusb': 'mixapplications/ultimate-usb-all-in-one-tool',
    'com.wellocution.androidapp': 'boldvoice/boldvoice-accent-training',
    'com.wanderlog.android': 'wanderlog/wanderlog-trip-planner-app',
    'tech.simha.androidtvremote': 'simha-software/remote-for-android-tv',
}


def request(url, data=None, headers=None, method=None):
    headers = {'User-Agent': USER_AGENT, **(headers or {})}
    if data is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(data).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=30) as response:
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
    releases = []
    for item in ET.fromstring(data).findall('./channel/item'):
        url = item.findtext('link', '')
        if not re.fullmatch(r'https://www\.apkmirror\.com/apk/' + re.escape(path) + r'/[\w-]+/', url):
            continue
        title = item.findtext('title', '')
        version = re.search(r'\s([0-9]+(?:\.[0-9]+)*(?:\s*(?:alpha|beta|rc)\s*\d*)?)\s+by\s+', title, re.I)
        released = parsedate_to_datetime(item.findtext('pubDate'))
        releases.append((version_key(version[1]) if version else (), released,
                         {'version': version[1] if version else None, 'url': url}))
    if not releases:
        raise ValueError('APKMirror feed has no matching releases')
    return max(releases, key=lambda release: (bool(release[0]), release[0], release[1]))[2]


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
    return {app['package_name']: {'version': app['version_name'], 'url': 'https://apkpure.com/p/' + app['package_name']}
            for app in data['app_update_response'] if app['package_name'] in packages}


def play_latest(data, package):
    html = data.decode('utf-8')
    match = re.search(r"key:\s*['\"]ds:5['\"].*?data:\s*", html, re.S)
    if not match:
        raise ValueError('Google Play app metadata missing')
    # shortcut: public ds:5 layout from google-play-scraper; review its mapping if Play changes the page.
    details = json.JSONDecoder().raw_decode(html[match.end():])[0]
    try:
        version = details[1][2][140][0][0][0]
    except (IndexError, TypeError):
        version = None
    if not isinstance(version, str) or not version.strip() or version == 'Varies with device':
        return {'status': 'Version unavailable'}
    return {'version': version, 'url': 'https://play.google.com/store/apps/details?id=' + package}


def play_listing(package):
    try:
        return play_latest(request(f'https://play.google.com/store/apps/details?id={package}&hl=en_US&gl=US'), package)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return {'status': 'No listing'}
        raise


def mirror_listing(package, feed):
    global next_mirror_search
    origin = 'https://www.apkmirror.com'
    if not feed:
        # shortcut: public APKMirror package search markup; update the matcher if the site changes.
        with MIRROR_SEARCH_LOCK:
            delay = next_mirror_search - monotonic()
            if delay > 0:
                sleep(delay)
            next_mirror_search = monotonic() + 2
            search = request(f'{origin}/?post_type=app_release&searchtype=app&s={quote(package)}').decode()
        paths = dict.fromkeys(re.findall(r'href="/apk/([\w-]+/[\w-]+)/"', search))
        matches = []
        for path in paths:
            page = request(f'{origin}/apk/{path}/').decode()
            if f'https://play.google.com/store/apps/details?id={package}"' in page:
                matches.append(path)
        if len(matches) != 1:
            return {'status': 'Ambiguous listing' if matches else 'No listing found'}
        feed = matches[0]
    return mirror_latest(request(f'{origin}/apk/{feed}/feed/'), feed)


def collect(apps, feeds):
    packages = sorted(apps)
    try:
        pure = pure_latest(packages)
    except SOURCE_ERRORS as error:
        print(f'APKPure check failed ({type(error).__name__})')
        pure = dict.fromkeys(packages, {'status': 'Check failed'})

    def check(package):
        sources = {'APKPure': pure.get(package, {'status': 'No listing found'})}
        for source, probe in (('APKMirror', partial(mirror_listing, package, feeds.get(package))),
                              ('Google Play', partial(play_listing, package))):
            try:
                sources[source] = probe()
            except urllib.error.HTTPError as error:
                print(f'{source} check failed for {package} (HTTP {error.code})')
                sources[source] = {'status': 'Rate limited' if error.code == 429 else 'Check failed'}
            except SOURCE_ERRORS as error:
                print(f'{source} check failed for {package} ({type(error).__name__})')
                sources[source] = {'status': 'Check failed'}
        return package, sources

    with ThreadPoolExecutor(max_workers=3) as pool:
        return dict(pool.map(check, packages))


def version_key(version):
    match = re.fullmatch(r'(\d+(?:\.\d+)*)(?:\s*(alpha|beta|rc)\s*(\d*))?', version, re.I)
    if not match:
        return ()
    numbers = tuple(map(int, match[1].split('.')))
    while len(numbers) > 1 and numbers[-1] == 0:
        numbers = numbers[:-1]
    stage = match[2].lower() if match[2] else None
    return numbers, ('alpha', 'beta', 'rc', None).index(stage), int(match[3] or 0)


def compare(package, listings, worked_on):
    available = [(source, listings[source]) for source in SOURCES if listings[source].get('version')]
    if not available:
        return None, [], 'No version available', 2
    versions = {listing['version'] for _, listing in available}
    ranked = [(source, version_key(listing['version'])) for source, listing in available]
    by_source = dict(ranked)
    pure = by_source.get('APKPure')
    reference = by_source.get('APKMirror') or by_source.get('Google Play') or version_key(worked_on)
    if package == 'com.linkedin.android' and pure and reference and pure[0][0] != reference[0][0]:
        return None, [], 'Needs review · APKPure version line differs', 1
    if len(versions) > 1 and any(not key for _, key in ranked):
        return None, [], 'Needs review · version formats differ', 1
    highest = max(key for _, key in ranked)
    winners = [source for source, key in ranked if key == highest]
    baseline = version_key(worked_on)
    if highest and baseline and highest > baseline:
        return winners[0], winners, 'Newer than worked on', 0
    if versions == {worked_on} or highest and highest == baseline:
        return winners[0], winners, 'Matches worked on', 2
    if highest and baseline:
        return winners[0], winners, 'Older than worked on', 2
    return winners[0], winners, 'Compare with worked-on version', 1


def text(value):
    return (str(value).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('|', '\\|').replace('[', '\\[').replace(']', '\\]')
            .replace('\n', ' ').replace('\r', ' ').replace('`', '').replace('@', '@\u200b'))


def dashboard(apps, listings_by_package, catalog_name):
    sections = ('Newer builds to review', 'Needs review', 'Other source comparisons')
    rows = [[], [], []]
    for package, app in sorted(apps.items(), key=lambda pair: pair[1]['name'].casefold()):
        listings = listings_by_package[package]
        selected, winners, status, section = compare(package, listings, app['supported'])
        cells = [f"**{text(app['name'])}**", text(app['supported'])]
        for source in SOURCES:
            item = listings[source]
            version = text(item.get('version') or item.get('status') or 'Version unavailable')
            cells.append(f'**{version}**' if source in winners else version)
        if selected:
            item = listings[selected]
            label = f"{selected} · {text(item['version'])}"
            tie = ' · all three tied' if len(winners) == 3 else ' · tied' if len(winners) == 2 else ''
            partial_check = ' · partial check' if any(item.get('status') in {'Check failed', 'Rate limited'}
                                                      for item in listings.values()) else ''
            cells.append(f"**[{label}]({item['url']})**{tie}{partial_check}")
        else:
            cells.append(status.removeprefix('Needs review · '))
        rows[section].append('| ' + ' | '.join(cells) + ' |')

    lines = [MARKER, f'## App update watch · {catalog_name}', '',
             'Latest available listings for the patch catalog. Worked-on versions are reviewed inputs or declared targets.', '']
    for heading, group in zip(sections, rows):
        if group:
            lines.extend((f'### {heading}', '',
                          '| App | Worked on | APKMirror | APKPure | Google Play (US) | Latest source |',
                          '| --- | --- | --- | --- | --- | --- |', *group, ''))
    lines.extend(('The link goes to the newest comparable listing. Ties prefer APKMirror, then APKPure, then Google Play.',
                  'Availability varies by device, region and rollout. Beta builds are labeled when the source exposes that information.', ''))
    return '\n'.join(lines)


def api(path, data=None, method=None):
    return json.loads(request('https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + path, data,
                              {'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                               'Accept': 'application/vnd.github+json'}, method))


def send_email(config, issue_url, catalog_name):
    message = EmailMessage()
    message['Subject'] = f'BadFella {catalog_name.lower()} app update watch changed'
    message['From'] = config.get('from') or config['username']
    message['To'] = config['to']
    message.set_content(f'The app update dashboard has changed.\n\n{issue_url}\n')
    context = ssl.create_default_context()
    port = int(config.get('port', 587))
    if port == 465:
        connection = smtplib.SMTP_SSL(config['host'], port, timeout=30, context=context)
    else:
        connection = smtplib.SMTP(config['host'], port, timeout=30)
    with connection as smtp:
        if port != 465:
            smtp.starttls(context=context)
        smtp.login(config['username'], config['password'])
        smtp.send_message(message)


def update_issue(path, previous, body, catalog_name, email_config):
    previous_body = previous['body'].rstrip()
    pending = previous_body.endswith(EMAIL_PENDING)
    if body.rstrip() != previous_body.removesuffix(EMAIL_PENDING).rstrip():
        api(path, {'body': body + '\n' + EMAIL_PENDING}, 'PATCH')
        pending = True
        print('Updated the dashboard in the issue description')
    if not pending:
        print('No dashboard changes')
        return
    if not email_config:
        print('Email pending: APP_UPDATE_SMTP_CONFIG is not configured')
        return
    try:
        send_email(json.loads(email_config), previous['html_url'], catalog_name)
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(f'Email delivery failed ({type(error).__name__}); will retry') from None
    api(path, {'body': body}, 'PATCH')
    print('Email accepted by the SMTP relay')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true', help='Print the dashboard without editing the issue')
    parser.add_argument('--catalog', type=Path, default=Path('patches-list.json'))
    parser.add_argument('--catalog-name', choices=['Public', 'Private'], default='Public')
    parser.add_argument('--reviewed-versions', type=Path)
    parser.add_argument('--mirror-feed', action='append', default=[], metavar='PACKAGE=DEVELOPER/APP')
    args = parser.parse_args()
    reviewed = json.loads(args.reviewed_versions.read_text(encoding='utf-8')) if args.reviewed_versions else {}
    apps = catalog_apps(json.loads(args.catalog.read_text(encoding='utf-8')), args.catalog_name, reviewed)
    feeds = FEEDS | dict(entry.split('=', 1) for entry in args.mirror_feed)
    if any(not re.fullmatch(r'[\w-]+/[\w-]+', feed) for feed in feeds.values()):
        raise ValueError('Invalid APKMirror feed path')
    issue = os.environ.get('APP_UPDATE_ISSUE', '')
    if not args.dry_run and not issue.isdecimal():
        raise ValueError('APP_UPDATE_ISSUE must identify the monitoring issue')
    if not args.dry_run and api('')['private'] != (args.catalog_name == 'Private'):
        raise ValueError('The catalog and monitoring repository visibility must match')
    path = f'/issues/{issue}'
    previous = {} if args.dry_run else api(path)
    if previous and ('pull_request' in previous or previous['title'] != 'Daily upstream app version alerts'):
        raise ValueError('APP_UPDATE_ISSUE points to a different issue')
    listings = collect(apps, feeds)
    body = dashboard(apps, listings, args.catalog_name)
    if args.dry_run:
        print(body)
    else:
        update_issue(path, previous, body, args.catalog_name, os.environ.get('APP_UPDATE_SMTP_CONFIG'))


if __name__ == '__main__':
    main()
