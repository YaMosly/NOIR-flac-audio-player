from pathlib import Path
import numpy as np
import soundfile as sf
from PIL import Image
from mutagen.flac import FLAC, Picture

root = Path(__file__).parent / 'music'
root.mkdir(exist_ok=True)
for i, (rate, subtype) in enumerate([(44100, 'PCM_16'), (96000, 'PCM_24'), (192000, 'PCM_24')]):
    # Quiet synthetic tones, never music from a third party.
    t = np.arange(rate * 20) / rate
    signal = .02 * np.sin(2 * np.pi * (220 + i * 110) * t)
    path = root / f'{i + 1:02d}-test-{rate}.flac'
    sf.write(path, np.column_stack([signal, signal]), rate, subtype=subtype)
    audio = FLAC(path)
    audio['title'] = ['После полуночи', 'Тихая орбита', 'Северный свет'][i]
    audio['artist'] = ['NOIR · тест 16/44.1', 'NOIR · тест 24/96', 'NOIR · тест 24/192'][i]
    audio['album'] = 'Тест воспроизведения'
    h, w = np.mgrid[0:600, 0:600] / 600
    ring = np.exp(-((np.hypot(w - .5, h - .5) - .25) / .07) ** 2)
    rgb = np.stack([.03 + ring * .13 + h * .10, .05 + ring * .5, .09 + ring * .58 + w * .10], axis=-1)
    image = Image.fromarray((rgb.clip(0, 1) * 255).astype('uint8'))
    import io
    buffer = io.BytesIO()
    image.save(buffer, format='JPEG', quality=92)
    pic = Picture(); pic.type = 3; pic.mime = 'image/jpeg'; pic.width = pic.height = 600; pic.depth = 24; pic.data = buffer.getvalue()
    audio.add_picture(pic); audio.save()
print(root)
