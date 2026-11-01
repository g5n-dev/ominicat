#!/usr/bin/env python3
"""Maintainer-only reproducible old-SPI fixture build; CI loads the frozen JAR.

Never compile the fixture against current sources during compatibility tests.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASELINE = 'd6698fa275006e40d7d69b2b1b1debc8deae1174'
SOURCE = ROOT / 'ominicat-core/src/test/fixtures/legacy-plugin/LegacyFactory.java'
OUTPUT = ROOT / 'ominicat-core/src/test/resources/legacy-plugin'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archive(path, entries):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as jar:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            jar.writestr(info, data)


def build(javac):
    version = subprocess.check_output([javac, '-version'], text=True, stderr=subprocess.STDOUT).strip()
    if not version.startswith('javac 17.'):
        raise ValueError('Build the legacy fixture with an actual Java 17 compiler.')
    paths = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', BASELINE,
        'ominicat-core/src/main/java'], cwd=ROOT, text=True).splitlines()
    sources = {p: subprocess.check_output(['git', 'show', BASELINE + ':' + p], cwd=ROOT)
               for p in paths if p.endswith('.java')}
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        inputs = []
        for name, data in sources.items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            inputs.append(str(target))
        classes, plugin = work / 'core-classes', work / 'plugin-classes'
        classes.mkdir(); plugin.mkdir()
        subprocess.run([javac, '--release', '17', '-d', str(classes), *inputs], check=True)
        baseline_jar = work / 'baseline-core.jar'
        archive(baseline_jar, {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob('*.class')})
        subprocess.run([javac, '--release', '17', '-cp', str(baseline_jar), '-d', str(plugin), str(SOURCE)], check=True)
        entries = {p.relative_to(plugin).as_posix(): p.read_bytes() for p in plugin.rglob('*.class')}
        entries['META-INF/services/io.ominicat.core.spi.ProcessorFactory'] = b'legacy.fixture.LegacyFactory\n'
        OUTPUT.mkdir(parents=True, exist_ok=True)
        destination = OUTPUT / 'legacy-plugin.jar'
        archive(destination, entries)
        metadata = dict(schemaVersion=1, baselineRevision=BASELINE, compiler=version, classFileMajor=61,
            sourceSha256=digest(SOURCE.read_bytes()), jarSha256=digest(destination.read_bytes()),
            baselineCoreJarSha256=digest(baseline_jar.read_bytes()),
            baselineSources={name: digest(data) for name, data in sorted(sources.items())},
            entries=sorted(entries))
        (OUTPUT / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    print('Built frozen Java 17 legacy plugin against ' + BASELINE)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--javac', required=True)
    args = parser.parse_args()
    build(args.javac)
