"""Run actual bundled quantized model through the same C++ generation core and prompts."""
import json,pathlib,re,subprocess,tempfile
root=pathlib.Path('app/src/main/assets')
templates=json.loads((root/'translation-prompts.json').read_text())
cases=[('ru-en','Я хочу сохранить этот файл.',[r'(save|keep)',r'file']),
       ('en-ru','Please open the window.',[r'откро',r'окн']),
       ('ru-uk','Спасибо за помощь.',[r'(дякую|спасибі|вдячн)',r'(допомог|підтрим)']),
       ('uk-ru','Будь ласка, зачекайте.',[r'пожалуйста',r'(подожд|ожида|погод)'])]
report=[]
for source,text,patterns in cases:
    pair=templates[source]
    prompt='<|im_start|>system\n'+pair['system']+'<|im_end|>\n'
    for a,b in pair['examples']:
        prompt+='<|im_start|>user\n'+a+'<|im_end|>\n<|im_start|>assistant\n'+b+'<|im_end|>\n'
    prompt+='<|im_start|>user\n'+text+'<|im_end|>\n<|im_start|>assistant\n'
    with tempfile.NamedTemporaryFile(mode='w',encoding='utf-8') as f:
        f.write(prompt);f.flush()
        result=subprocess.run(['build-smoke/translation-smoke',str(root/'model.gguf'),f.name],capture_output=True,text=True,timeout=180)
    if result.returncode:
        raise RuntimeError(result.stderr[-3000:])
    translated=result.stdout.strip()
    print(source,repr(text),'->',repr(translated),flush=True)
    assert translated and len(translated)<1000
    for pattern in patterns:assert re.search(pattern,translated,re.I),f'{source}: unexpected meaning: {translated}'
    report.append(dict(direction=source,source=text,translation=translated))
pathlib.Path('TRANSLATION_TESTS.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('PASS: actual offline model, all four requested translation directions')
