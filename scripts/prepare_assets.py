"""Download pinned public AI weights and a dated snapshot of the official statute text."""
import hashlib,json,pathlib,re,time,urllib.request
from datetime import datetime,timezone
from html.parser import HTMLParser
root=pathlib.Path("app/src/main/assets");root.mkdir(parents=True,exist_ok=True)
lic=root/"licenses";lic.mkdir(exist_ok=True)
revision="91cad51170dc346986eccefdc2dd33a9da36ead9"
base=f"https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/{revision}/"
def download(url,path):
    for attempt in range(3):
        try:
            req=urllib.request.Request(url,headers={"User-Agent":"UstavyStudy/0.1"})
            with urllib.request.urlopen(req,timeout=90) as response,open(str(path)+".tmp","wb") as out:
                while chunk:=response.read(1024*1024):out.write(chunk)
            pathlib.Path(str(path)+".tmp").replace(path);return
        except Exception:
            if attempt==2:raise
            time.sleep(3)
download(base+"qwen2.5-1.5b-instruct-q4_k_m.gguf",root/"model.gguf")
with open(root/"model.gguf","rb") as stream:
    assert stream.read(4)==b"GGUF","Invalid GGUF model"
with open(root/"model.gguf","rb") as stream:digest=hashlib.file_digest(stream,"sha256").hexdigest()
assert (root/"model.gguf").stat().st_size>1000000000
print("Model:",(root/"model.gguf").stat().st_size,"bytes; sha256",digest,flush=True)
download(base+"LICENSE",lic/"QWEN-LICENSE.txt")
(lic/"LLAMA-LICENSE.txt").write_bytes(pathlib.Path("vendor/llama.cpp/LICENSE").read_bytes())
(lic/"NOTICE.txt").write_text("Qwen2.5-1.5B-Instruct-GGUF — Qwen / Alibaba Cloud. Apache-2.0.\n"+base+"\nSHA256 "+digest+"\n\nllama.cpp — MIT, ggml contributors.\nhttps://github.com/ggml-org/llama.cpp\n\n"+(lic/"QWEN-LICENSE.txt").read_text()+"\n\n"+(lic/"LLAMA-LICENSE.txt").read_text(),encoding="utf-8")
class Extract(HTMLParser):
    def __init__(self):super().__init__();self.text=[];self.skip=0
    def handle_starttag(self,tag,attrs):
        if tag in ("script","style"):self.skip+=1
        if tag in ("p","br","div","h1","h2","h3","li","tr"):self.text.append("\n")
    def handle_endtag(self,tag):
        if tag in ("script","style"):self.skip=max(0,self.skip-1)
        if tag in ("p","div","h1","h2","h3","li","tr"):self.text.append("\n")
    def handle_data(self,data):
        if not self.skip:self.text.append(data)
def get_text(url):
    req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0"})
    with urllib.request.urlopen(req,timeout=35) as r:
        raw=r.read(12*1024*1024);charset=r.headers.get_content_charset()
    if not charset:
        m=re.search(br'charset=["\s]*([a-zA-Z0-9_-]+)',raw[:20000]);charset=m.group(1).decode() if m else "utf-8"
    html=raw.decode(charset,errors="replace")
    if html.count("\ufffd")>100:html=raw.decode("cp1251",errors="replace")
    p=Extract();p.feed(html);return re.sub(r"[ \t\xa0]+"," ","".join(p.text))
urls=[
 "https://pravo.gov.ru/proxy/ips/?doc_itself=&nd=102118429&page=1&rdk=0",
 "http://pravo.gov.ru/proxy/ips/?doc_itself=&nd=102118429&page=1&rdk=0",
 "https://www.kremlin.ru/acts/bank/26528/page/all",
 "http://www.kremlin.ru/acts/bank/26528/page/all"
]
markers=[
 ("internal","Устав внутренней службы",r"УСТАВ\s+ВНУТРЕННЕЙ\s+СЛУЖБЫ\s+ВООРУЖЕННЫХ\s+СИЛ\s+РОССИЙСКОЙ\s+ФЕДЕРАЦИИ"),
 ("discipline","Дисциплинарный устав",r"ДИСЦИПЛИНАРНЫЙ\s+УСТАВ\s+ВООРУЖЕННЫХ\s+СИЛ\s+РОССИЙСКОЙ\s+ФЕДЕРАЦИИ"),
 ("garrison","Устав гарнизонной и караульной служб",r"УСТАВ\s+ГАРНИЗОННОЙ(?:,?\s+КОМЕНДАНТСКОЙ)?\s+И\s+КАРАУЛЬНОЙ\s+СЛУЖБ\s+ВООРУЖЕННЫХ\s+СИЛ\s+РОССИЙСКОЙ\s+ФЕДЕРАЦИИ")
]
documents=[];errors=[]
for url in urls:
    try:
        text=get_text(url);found=[]
        for ident,name,pattern in markers:
            matches=list(re.finditer(pattern,text))
            if not matches:raise ValueError("Missing heading: "+name)
            found.append((matches[-1].start(),ident,name))
        found.sort()
        for i,(start,ident,name) in enumerate(found):
            part=text[start:found[i+1][0] if i+1<len(found) else len(text)].strip()
            count=len(re.findall(r"(?m)^\s*\d+(?:\.\d+)*\.\s",part))
            if count<80:raise ValueError(f"Insufficient numbered paragraphs: {name}: {count}")
            documents.append({"id":ident,"name":name,"source":url,"note":"Указ №1495 от 10.11.2007. Снимок источника от "+datetime.now(timezone.utc).strftime("%d.%m.%Y")+". Актуальность редакции отдельно не подтверждена.","text":part})
        break
    except Exception as error:
        documents=[];errors.append({"url":url,"error":str(error)});print("Source unavailable:",url,str(error),flush=True)
status="" if documents else "Не удалось загрузить официальные тексты при сборке. Добавь TXT через библиотеку."
(root/"corpus.json").write_text(json.dumps({"documents":documents,"status":status},ensure_ascii=False),encoding="utf-8")
pathlib.Path("ASSET_REPORT.json").write_text(json.dumps({"model_sha256":digest,"documents":[{"name":d["name"],"characters":len(d["text"])} for d in documents],"source_errors":errors},ensure_ascii=False,indent=2),encoding="utf-8")
print("Bundled documents:",len(documents),flush=True)
