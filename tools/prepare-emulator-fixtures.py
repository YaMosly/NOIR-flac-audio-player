"""Prepare synthetic tracks for PlaybackSmokeTest on the dedicated NOIR emulator only."""
from pathlib import Path
from mutagen.flac import FLAC
import json
import subprocess
import sys
import base64
import hashlib
import shlex

adb = sys.argv[1]
serial = sys.argv[2] if len(sys.argv) > 2 else 'emulator-5554'
def run(*args, **kwargs):
    return subprocess.run([adb, '-s', serial, *args], check=True, capture_output=True, **kwargs).stdout
assert run('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1', 'Only the isolated emulator is supported'
assert b'noir-api36' in run('emu', 'avd', 'name'), 'Use the dedicated NOIR AVD'
app_root = run('shell', 'run-as', 'dev.noir.player', 'pwd').decode().strip()
folder = app_root + '/files/test-media'
run('shell', 'run-as', 'dev.noir.player', 'mkdir', '-p', folder)
tracks = []
for file in sorted((Path(__file__).parent / 'music').glob('*.flac')):
    data = file.read_bytes()
    encoded = folder + '/' + file.name + '.base64'
    destination = folder + '/' + file.name
    # Windows stdin for adb shell can treat binary control bytes as EOF. Transfer ASCII, then decode on device.
    run('shell', 'run-as', 'dev.noir.player', 'tee', encoded, input=base64.b64encode(data))
    command = 'base64 -d ' + shlex.quote(encoded) + ' > ' + shlex.quote(destination)
    run('shell', 'run-as dev.noir.player sh -c ' + shlex.quote(command))
    received = run('shell', 'run-as', 'dev.noir.player', 'sha256sum', destination).decode().split()[0]
    assert received == hashlib.sha256(data).hexdigest(), 'Fixture transfer changed bytes'
    audio = FLAC(file)
    tracks.append(dict(id='file://' + folder + '/' + file.name, title=audio['title'][0],
        artist=audio['artist'][0], album=audio['album'][0], duration=round(audio.info.length * 1000),
        rate=audio.info.sample_rate, bits=audio.info.bits_per_sample, channels=audio.info.channels))
assert len(tracks) == 3, 'Run create-fixtures.py first'
run('shell', 'run-as', 'dev.noir.player', 'mkdir', '-p', 'files')
run('shell', 'run-as', 'dev.noir.player', 'tee', 'files/library.json', input=json.dumps(tracks).encode())
print('Prepared 3 synthetic FLAC tracks in the isolated emulator')
