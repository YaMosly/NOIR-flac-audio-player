"""Hardware-free regression on an explicitly named dedicated NOIR emulator."""
from pathlib import Path
import argparse, re, subprocess, sys

p=argparse.ArgumentParser()
p.add_argument('adb');p.add_argument('serial');p.add_argument('--release',action='store_true')
args=p.parse_args()
root=Path(__file__).resolve().parent.parent
logs=root/'build'/'emulator-checks';logs.mkdir(parents=True,exist_ok=True)
def adb(*parts):
    return subprocess.run([args.adb,'-s',args.serial,*parts],check=True,capture_output=True,text=True).stdout
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','Requires the dedicated emulator'
assert adb('emu','avd','name').splitlines()[0].strip() in {'NoirCompat37','NoirCompat36','noir-api36'},'Refusing an unrelated AVD'
adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk'))
adb('install','-r',str(root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
adb('shell','am','force-stop','dev.noir.player')
subprocess.run([sys.executable,str(root/'tools/prepare-emulator-fixtures.py'),args.adb,args.serial],check=True)
classes=['UsbTransportNativeTest','ReleaseUsbVerificationTest','UsbPlayerIntegrationTest','QueueAndPreferencesTest','DailyPlaybackIntegrationTest','QueueUiTest','AlbumUiTest','WidgetHostTest','PlaybackSmokeTest']
def instrument(classes,name):
    output=adb('shell','am','instrument','-w','-e','class',','.join('dev.noir.player.'+c for c in classes),'dev.noir.player.test/androidx.test.runner.AndroidJUnitRunner')
    (logs/name).write_text(output,encoding='utf-8');print(output)
    if not re.search(r'OK \(\d+ tests?\)',output) or 'FAILURES' in output or 'INSTRUMENTATION_FAILED' in output:
        raise SystemExit('Instrumentation did not report success')
try:
    adb('shell','appwidget','grantbind','--package','dev.noir.player','--user','0')
    instrument(classes,'regression.txt')
    if args.release:
        adb('shell','am','force-stop','dev.noir.player')
        adb('install','-r',str(root/'app/build/outputs/apk/release/app-release.apk'))
        instrument(['ReleaseUsbVerificationTest'],'release.txt')
finally:
    adb('shell','appwidget','revokebind','--package','dev.noir.player','--user','0')
    (logs/'logcat.txt').write_text(adb('logcat','-d'),encoding='utf-8')
