from pathlib import Path
import numpy as np, soundfile as sf, hashlib, json, subprocess
import sys
root=Path(__file__).resolve().parent.parent
assets=root/'app/src/androidTest/assets/hires'; assets.mkdir(parents=True,exist_ok=True)
exe=Path(sys.argv[1]).resolve()
manifest=[]
for rate in [44100,48000,88200,96000,176400,192000,352800,384000]:
 for bits in [16,24,32]:
  n=np.arange(rate,dtype=np.int64)
  # Differing channels and low bits, signed extrema and zero: truncation/packing is visible.
  data=np.column_stack([((n*131+17)%(1<<bits))-(1<<(bits-1)), ((n*173+23)%(1<<bits))-(1<<(bits-1))]).astype('<i4')
  data[:5]=np.array([[0,0],[1,-1],[(1<<(bits-1))-1,-(1<<(bits-1))],[12345,-12345],[-2,2]])
  raw=data.astype('<i2').tobytes() if bits==16 else (data.view('u1').reshape(-1,4)[:,:3].copy().tobytes() if bits==24 else data.tobytes())
  source=Path(__file__).parent / 'fixture-pcm.raw';source.write_bytes(raw)
  name=f'{bits}-{rate}.flac';dest=assets/name
  subprocess.run([str(exe),'--silent','--force','--force-raw-format','--endian=little','--sign=signed','--channels=2',f'--bps={bits}',f'--sample-rate={rate}','--lax','-o',str(dest),str(source)],check=True)
  manifest.append(dict(file=name,rate=rate,bits=bits,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
# Independent libsndfile integer reference for existing full 24-bit tracks.
for path in (Path(__file__).parent / 'music').glob('*.flac'):
 data,rate=sf.read(path,dtype='int32',always_2d=True)
 if rate==44100:continue
 packed=(data>>8).astype('<i4').view('u1').reshape(-1,4)[:,:3].copy().tobytes()
 manifest.append(dict(file=path.name,rate=rate,bits=24,bytes=len(packed),sha256=hashlib.sha256(packed).hexdigest(),library=True))
(assets/'manifest.json').write_text(json.dumps(manifest))
print('Generated 24 exact integer FLAC profiles and independent hashes.')

source.unlink(missing_ok=True)
