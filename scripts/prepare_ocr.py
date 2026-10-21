"""Bundle pinned Apache-2.0 Tesseract fast models, verified against Git blob IDs."""
import hashlib
import pathlib
import urllib.request

REV = '87416418657359cb625c412a48b6e1d6d41c29bd'
MODELS = {'rus': (3861738, 'b146cb2263acbc6383f8e92ea0ce759537687bb8'),
          'eng': (4113088, 'bbef4675053b5b468cdb477053e28b1c698ba08e')}
root = pathlib.Path('app/src/main/assets/tessdata')
root.mkdir(parents=True, exist_ok=True)
for lang, (size, digest) in MODELS.items():
    target = root / (lang + '.traineddata')
    data = target.read_bytes() if target.exists() else urllib.request.urlopen(
        f'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/{REV}/{lang}.traineddata', timeout=90).read()
    assert len(data) == size
    assert hashlib.sha1(f'blob {size}\0'.encode() + data).hexdigest() == digest
    target.write_bytes(data)
    print(f'Verified offline OCR model: {lang}, {size} bytes')
