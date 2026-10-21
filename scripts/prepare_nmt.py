"""Bundle only pinned quantized translation models; validate every file against HF Git/LFS hashes."""
import concurrent.futures,hashlib,json,pathlib,shutil,subprocess,time,urllib.request
root=pathlib.Path('app/src/main/assets');out=root/'nmt';out.mkdir(parents=True,exist_ok=True)
REVISIONS={'ru-uk':'6d8a744ed6eafd9003bcc2b052c4575de0485e1e','uk-ru':'cfb76ccd207734d7847679664fa4538cda5ed597','ru-en':'afe8c6c738ec81b6d033fd8f44f9678a639a7c67','en-ru':'e050376e960175e8ddc5cff85025dc8436cddd68'}
FILES=['config.json','generation_config.json','tokenizer.json','tokenizer_config.json','special_tokens_map.json','onnx/encoder_model_quantized.onnx','onnx/decoder_model_merged_quantized.onnx']
def get(url):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'UstavyOffline/0.7'}),timeout=120) as response:return response.read()
        except Exception:
            if attempt==2:raise
            time.sleep(2)
records=[]
for pair,revision in REVISIONS.items():
    repo='Xenova/opus-mt-'+pair
    metadata=json.loads(get(f'https://huggingface.co/api/models/{repo}/tree/{revision}?recursive=true&expand=true'))
    entries={item['path']:item for item in metadata}
    def prepare(name):
        item=entries[name];target=out/pair/name;target.parent.mkdir(parents=True,exist_ok=True)
        data=target.read_bytes() if target.exists() else get(f'https://huggingface.co/{repo}/resolve/{revision}/{name}')
        assert len(data)==item['size'],(name,len(data),item['size'])
        if item.get('lfs'):
            assert hashlib.sha256(data).hexdigest()==item['lfs']['oid'],name
        else:assert hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest()==item['oid'],name
        target.write_bytes(data)
        return dict(pair=pair,file=name,revision=revision,size=len(data),sha256=hashlib.sha256(data).hexdigest())
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:records.extend(pool.map(prepare,FILES))
    print('Verified bundled translation model',repo,flush=True)
# Prebuilt browser bundle, no NPM lifecycle scripts, no runtime network loaders.
package=pathlib.Path('/tmp/ustavy-nmt-package')
subprocess.run(['npm','install','--prefix',str(package),'--ignore-scripts','--omit=optional','@xenova/transformers@2.17.2','onnxruntime-web@1.14.0'],check=True)
lib=root/'nmt-runtime';lib.mkdir(exist_ok=True)
modules=package/'node_modules'
shutil.copyfile(modules/'@xenova/transformers/dist/transformers.min.js',lib/'transformers.min.js')
for file in (modules/'onnxruntime-web/dist').glob('*.wasm'):shutil.copyfile(file,lib/file.name)
licenses=root/'licenses';licenses.mkdir(exist_ok=True)
shutil.copyfile(modules/'@xenova/transformers/LICENSE',licenses/'TRANSFORMERS-JS-LICENSE.txt')
# OPUS-MT model cards and upstream licenses are bundled for attribution.
for pair in REVISIONS:
    (licenses/f'OPUS-MT-{pair}-README.txt').write_bytes(get(f'https://huggingface.co/Helsinki-NLP/opus-mt-{pair}/raw/main/README.md'))
(licenses/'CC-BY-4.0.txt').write_bytes(get('https://creativecommons.org/licenses/by/4.0/legalcode.txt'))
(licenses/'NMT-NOTICE.txt').write_text('Translation models: Helsinki-NLP OPUS-MT; licenses differ by pair, see bundled upstream model cards. Russian-English: CC-BY-4.0; English-Russian and Ukrainian-Russian: Apache-2.0. ONNX conversions: Xenova.\nhttps://creativecommons.org/licenses/by/4.0/\nRuntime: Transformers.js 2.17.2 Apache-2.0; ONNX Runtime Web 1.14.0 MIT.\nPinned weights and hashes: NMT_ASSET_REPORT.json.\n',encoding='utf-8')
(licenses/'ONNX-RUNTIME-LICENSE.txt').write_bytes(get('https://raw.githubusercontent.com/microsoft/onnxruntime/v1.14.0/LICENSE'))
pathlib.Path('NMT_ASSET_REPORT.json').write_text(json.dumps(records,indent=2))
print('Bundled translation bytes',sum(item['size'] for item in records),flush=True)
