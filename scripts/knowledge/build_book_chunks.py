"""把已抽取/OCR 的逐页原文构造成现有 KnowledgeItem 格式；不调用模型、不改业务文件。

输入：inspect_books.py 的 inventory.json、每书 text-pages.json 和 ocr/NNNN.json。
输出独立 new-items.json、manifest.json、压缩逐页原文，可在验证后与旧库合并。
"""
import argparse, collections, gzip, hashlib, json, pathlib, re

CHINESE=r'\u4e00-\u9fff'
WATERMARKS=('夸克扫描王','极速扫描','就是高效','z-library.sk','1lib.sk','z-lib.sk')


def clean(text, running_headers=()):
    text=text.replace('\r\n','\n').replace('\r','\n').replace('\x00','')
    text=re.sub(r'(?m)^\s*学\s*\n\s*习\s*\n\s*笔\s*\n\s*记\s*$','',text)
    lines=[]
    for raw in text.splitlines():
        line=raw.strip()
        if line=='学习笔记' or line in running_headers or any(w.lower() in line.lower() for w in WATERMARKS): continue
        if re.fullmatch(r'[-—\s]*\d{1,4}[-—\s]*',line): continue
        line=re.sub(r'(?<=['+CHINESE+r'])[^\S\n]+(?=['+CHINESE+r'])','',line)
        lines.append(line)
    return re.sub(r'\n{3,}','\n\n','\n'.join(lines)).strip()


def split_chunks(text, size=600, overlap=90):
    if size<100 or overlap<0 or overlap>=size: raise ValueError('Invalid chunk parameters')
    pieces=[]; start=0
    while start<len(text):
        end=min(start+size,len(text))
        if end<len(text):
            # 优先在完整句末截断，保留重叠以覆盖边界处的条件与定义。
            lower=start+size//2
            points=[text.rfind(mark,lower,end) for mark in ('。','！','？','；','\n')]
            cut=max(points)
            if cut>=lower: end=cut+1
        piece=text[start:end].strip()
        if piece: pieces.append(piece)
        if end>=len(text): break
        start=max(start+1,end-overlap)
    return pieces


def item_of(book, page, index, text):
    # 不以知识点名称去重；同名跨页论述和同名不同书都完整保留。
    digest=hashlib.sha256(text.encode('utf-8')).hexdigest()[:16]
    book_id=book['sha256'][:20]
    first=re.split(r'[。！？；\n]',text)[0].strip(' ，、：:')[:42]
    title=book['source'].removesuffix('.pdf')
    return {'id':f'book:{book_id}:p{page}:c{index}:{digest}',
            'name':f'{title}｜{first}（p{page}-{index}）','detail':text,
            'source':book['source'],'location':f'第 {page} 页','type':'pdf'}


def is_contents(text):
    lines=[line for line in text.splitlines() if line.strip()]
    # 正文中的省略号不代表目录；必须多行以点线+独立页码结尾。
    entries=sum(bool(re.search(r'(?:\.{3,}|…{2,}|·{5,})\s*\d{1,4}\s*$',line)) for line in lines)
    return entries>=5 and entries/max(1,len(lines))>=.35


def build(root, output, allow_pending=False):
    inventory=json.loads((root/'inventory.json').read_text(encoding='utf-8'))
    if len(inventory)!=6 or len({r['source'] for r in inventory})!=6: raise ValueError('Expected six unique books')
    items=[]; books=[]; provenance=[]
    for book in inventory:
        if hashlib.sha256(pathlib.Path(book['path']).read_bytes()).hexdigest()!=book['sha256']:
            raise ValueError('Source PDF changed: '+book['key'])
        pages=json.loads((root/book['key']/'text-pages.json').read_text(encoding='utf-8'))
        pending=[]
        for page in pages:
            refined=root/book['key']/'ocr-refined'/f'{page["page"]:04d}.json'
            if book.get('preferRefinedOcr'):
                if not refined.is_file(): pending.append(page['page']);continue
                read=json.loads(refined.read_text(encoding='utf-8'))
                page['text']=read['text'];page['method']=read['method']
                continue
            path=root/book['key']/'ocr'/f'{page["page"]:04d}.json'
            if page['page'] in book['lowTextPages']:
                if not path.is_file(): pending.append(page['page']); continue
                read=json.loads(path.read_text(encoding='utf-8'))
                # 低中文字数但仍有英文/稀疏内容时，保留字符更多的原文，防止 OCR 将可取文字抹掉。
                if len(re.sub(r'\s','',read['text']))>page['chars'] or book['textChars']==0:
                    page['text']=read['text']; page['method']=read['method']
            page.setdefault('method','pdf-text-layer')
        if pending and not allow_pending: raise ValueError(f'Unprocessed OCR: {book["key"]} {pending[:10]}')
        header_counts=collections.Counter()
        for page in pages:
            lines=[line.strip() for line in page['text'].splitlines() if line.strip()]
            header_counts.update(set(line for line in lines[:2]+lines[-2:] if 2<=len(line)<=35))
        headers={line for line,count in header_counts.items() if count>=max(8,len(pages)*.12)}
        summary={'key':book['key'],'source':book['source'],'pdfSHA256':book['sha256'],'totalPages':len(pages),
                 'ocrPages':0,'pendingPages':pending,'retrievablePages':0,'excluded':[],'chunks':0,'characters':0,
                 'removedRunningHeaders':sorted(headers)}
        for page in pages:
            if page['page'] in pending: continue
            text=clean(page['text'],headers)
            chinese=len(re.findall('['+CHINESE+']',text))
            reason=None
            if chinese<10 or len(re.sub(r'\s','',text))<20: reason='sparse/title/blank'
            elif is_contents(text): reason='table-of-contents'
            if 'rapidocr' in page['method']: summary['ocrPages']+=1
            provenance.append({'book':book['key'],'page':page['page'],'method':page['method'],'text':text,
                               'excludedReason':reason,'textSHA256':hashlib.sha256(text.encode()).hexdigest()})
            if reason:
                summary['excluded'].append({'page':page['page'],'reason':reason,'chinese':chinese}); continue
            summary['retrievablePages']+=1; summary['characters']+=len(text)
            for index,piece in enumerate(split_chunks(text),1):
                if len(piece)<20: continue
                items.append(item_of(book,page['page'],index,piece)); summary['chunks']+=1
        if summary['chunks']==0 and not allow_pending: raise ValueError('Empty book')
        books.append(summary)
    if len({i['id'] for i in items})!=len(items): raise ValueError('Duplicate chunk IDs')
    output.mkdir(parents=True,exist_ok=True)
    payload=json.dumps(items,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    (output/'new-items.json').write_bytes(payload)
    with gzip.open(output/'source-pages.json.gz','wt',encoding='utf-8') as f: json.dump(provenance,f,ensure_ascii=False)
    manifest={'schema':'lamplit-raw-page-chunks-v1','chunkSize':600,'overlap':90,'physicalPdfPages':True,
              'items':len(items),'sources':books,'itemsSHA256':hashlib.sha256(payload).hexdigest(),
              'sourcePageArchiveSHA256':hashlib.sha256((output/'source-pages.json.gz').read_bytes()).hexdigest(),
              'embeddingInputUTF8Bytes':sum(len((i['name']+' '+i['detail']).encode()) for i in items),
              'complete':not any(b['pendingPages'] for b in books)}
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in manifest.items() if k!='sources'},ensure_ascii=False))
    for book in books: print(json.dumps({k:v for k,v in book.items() if k not in ('excluded','pendingPages','removedRunningHeaders','pdfSHA256')},ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('root',type=pathlib.Path); parser.add_argument('output',type=pathlib.Path)
    parser.add_argument('--allow-pending',action='store_true'); args=parser.parse_args()
    build(args.root,args.output,args.allow_pending)
