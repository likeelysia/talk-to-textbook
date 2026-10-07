/* ---------------------------------------------------------------------
   Formal Agent 前端逻辑(原生 JS,无框架)

   它只干三件事:
     1. 把用户输入(文字 / 一批图片)塞进 HTTP 请求
     2. 把 JSON 响应渲染成气泡
     3. 管好"待发送图片"这份小状态(选、压缩、删、发)
   业务全在后端 —— 前端就是个"遥控器"。
   --------------------------------------------------------------------- */

const messagesEl = document.getElementById('messages');
const formEl = document.getElementById('ask-form');
const questionEl = document.getElementById('question');
const imagesEl = document.getElementById('images');
const pickLabel = document.getElementById('pickLabel');
const previewsEl = document.getElementById('previews');
const thumbsEl = document.getElementById('thumbs');
const imgCountEl = document.getElementById('imgCount');
const previewMsgEl = document.getElementById('previewMsg');
const clearBtn = document.getElementById('clearImages');
const sendBtn = document.getElementById('send');

/* ---------------- 可调参数 ---------------- */

const MAX_IMAGES = 10;                       // 一次最多几张(和后端 AgentController.MAX_IMAGES 保持一致)
const MAX_RAW_BYTES = 20 * 1024 * 1024;      // 单张原图上限 20MB
const MAX_EDGE = 1280;                       // 压缩后长边上限(像素)
const JPEG_QUALITY = 0.85;                   // 压缩质量
const KEEP_AS_IS_BYTES = 512 * 1024;         // 又小又不超长边的图,不折腾,原样发

/**
 * 待发送的图片。每一项 = { file: File(压缩后的), url: 预览用的 objectURL }
 * ⚠️ url 是 URL.createObjectURL 产生的,**不用了必须 revoke**,否则内存泄漏。
 */
let picked = [];

/* ---------------- 小工具 ---------------- */

function escapeHtml(s) {
    return (s ?? '').replace(/[&<>"']/g,
        c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function addBubble(className, html) {
    const div = document.createElement('div');
    div.className = 'bubble ' + className;
    div.innerHTML = html;
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return div;
}

async function readError(res) {
    try {
        const j = await res.json();
        return j.error || JSON.stringify(j);
    } catch {
        return res.status + ' ' + res.statusText;
    }
}

let hintTimer = null;
function setHint(text) {
    previewMsgEl.textContent = text || '';
    clearTimeout(hintTimer);
    if (text) hintTimer = setTimeout(() => { previewMsgEl.textContent = ''; }, 5000);
}

/* ---------------- 渲染回答(含工具轨迹) ---------------- */

function renderAnswer(res) {
    const answer = escapeHtml(res.answer || '(空回答)').replace(/\n/g, '<br>');

    let trace = '<div class="muted small">(这题不需要查资料,直接回答)</div>';
    if (res.trace && res.trace.length) {
        const items = res.trace.map(t => `<li><code>${escapeHtml(t)}</code></li>`).join('');
        trace = `<details class="trace"><summary>它调用了 ${res.trace.length} 次工具(共 ${res.steps} 步)</summary>
                     <ol>${items}</ol></details>`;
    }
    return `<div class="answer">${answer}</div>${trace}`;
}

/* ---------------- 图片:压缩 ---------------- */

function loadImageElement(file) {
    return new Promise((resolve, reject) => {
        const url = URL.createObjectURL(file);
        const img = new Image();
        img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
        img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片解码失败')); };
        img.src = url;
    });
}

/**
 * 上传前先在浏览器里压一遍:长边超过 MAX_EDGE 就等比缩小,再编成 JPEG。
 * 好处:体积能降 80%+,视觉模型识别更快、请求体更小。
 * 压不动(解码失败等)就原样返回,别让一张图毁掉整批。
 */
async function compressImage(file) {
    try {
        const img = await loadImageElement(file);
        const longEdge = Math.max(img.naturalWidth, img.naturalHeight);
        const scale = Math.min(1, MAX_EDGE / longEdge);
        if (scale === 1 && file.size <= KEEP_AS_IS_BYTES) return file;

        const w = Math.max(1, Math.round(img.naturalWidth * scale));
        const h = Math.max(1, Math.round(img.naturalHeight * scale));
        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        canvas.getContext('2d').drawImage(img, 0, 0, w, h);

        const blob = await new Promise(r => canvas.toBlob(r, 'image/jpeg', JPEG_QUALITY));
        if (!blob || blob.size >= file.size) return file;      // 压完反而更大?那就用原图
        const name = file.name.replace(/\.[^.]+$/, '') + '.jpg';
        return new File([blob], name, { type: 'image/jpeg' });
    } catch {
        return file;
    }
}

/* ---------------- 图片:增 / 删 / 渲染 ---------------- */

async function addFiles(list) {
    let notImage = 0, tooBig = 0, overflow = 0;

    for (const raw of list) {
        if (!raw.type.startsWith('image/')) { notImage++; continue; }
        if (raw.size > MAX_RAW_BYTES) { tooBig++; continue; }
        if (picked.length >= MAX_IMAGES) { overflow++; continue; }

        const file = await compressImage(raw);
        picked.push({ file, url: URL.createObjectURL(file) });
    }

    renderPreviews();

    const msgs = [];
    if (overflow) msgs.push(`最多 ${MAX_IMAGES} 张,有 ${overflow} 张没加进来`);
    if (notImage) msgs.push(`${notImage} 个文件不是图片`);
    if (tooBig) msgs.push(`${tooBig} 张超过 ${Math.round(MAX_RAW_BYTES / 1024 / 1024)}MB`);
    setHint(msgs.join(';'));
}

function removeAt(index) {
    const [gone] = picked.splice(index, 1);
    if (gone) URL.revokeObjectURL(gone.url);        // 删了就把 URL 释放掉
    renderPreviews();
}

function clearPicked() {
    picked.forEach(p => URL.revokeObjectURL(p.url));
    picked = [];
    renderPreviews();
}

function renderPreviews() {
    thumbsEl.innerHTML = '';
    picked.forEach((item, i) => {
        const box = document.createElement('div');
        box.className = 'thumb';

        const img = document.createElement('img');
        img.src = item.url;
        img.alt = item.file.name;

        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'del';
        del.title = '移除这张';
        del.textContent = '×';
        del.addEventListener('click', () => removeAt(i));

        box.append(img, del);
        thumbsEl.appendChild(box);
    });

    previewsEl.hidden = picked.length === 0;
    imgCountEl.textContent = `${picked.length} / ${MAX_IMAGES}`;
    pickLabel.classList.toggle('disabled', picked.length >= MAX_IMAGES);
}

imagesEl.addEventListener('change', async () => {
    const list = Array.from(imagesEl.files || []);
    imagesEl.value = '';               // 清空,这样再选同一个文件也能触发 change
    if (list.length) await addFiles(list);
});

clearBtn.addEventListener('click', () => { setHint(''); clearPicked(); });

/* ---------------- 提问 ---------------- */

function bubbleThumbsHtml() {
    // 注意:这里沿用 picked 里的 objectURL,把"所有权"交给气泡里的 <img>();
    // 发出去之后不再 revoke(否则气泡里的图会变空白)。
    const cells = picked.map(p => `<div class="thumb"><img src="${p.url}" alt=""></div>`).join('');
    return `<div class="thumbs">${cells}</div>`;
}

formEl.addEventListener('submit', async (e) => {
    e.preventDefault();
    const question = questionEl.value.trim();
    if (!question && !picked.length) return;

    const filesToSend = picked.map(p => p.file);
    const thumbs = picked.length ? bubbleThumbsHtml() : '';
    addBubble('user', thumbs + (question ? `<div>${escapeHtml(question)}</div>`
                                         : `<div>(${filesToSend.length} 张图片)</div>`));

    // 状态清掉:文字框、待发送数组、缩略图区
    questionEl.value = '';
    picked = [];                       // 不 revoke —— URL 已被上面的气泡接管
    renderPreviews();
    setHint('');

    const bubble = addBubble('bot thinking', '正在思考…');
    const startedAt = Date.now();
    const timer = setInterval(() => {
        const secs = Math.round((Date.now() - startedAt) / 1000);
        const est = filesToSend.length
            ? `(要识别 ${filesToSend.length} 张图,大约 ${filesToSend.length * 12} 秒)`
            : '';
        bubble.textContent = `正在思考…已等待 ${secs}s ${est}`;
    }, 1000);

    sendBtn.disabled = true;
    try {
        const result = filesToSend.length
            ? await askImages(filesToSend, question)
            : await askText(question);
        bubble.className = 'bubble bot';
        bubble.innerHTML = renderAnswer(result);
    } catch (err) {
        bubble.className = 'bubble bot error';
        bubble.textContent = '出错了:' + err.message;
    } finally {
        clearInterval(timer);
        sendBtn.disabled = false;
    }
});

/* ---------------- 两个接口调用 ---------------- */

async function askText(question) {
    const res = await fetch('/api/agent', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question })
    });
    if (!res.ok) throw new Error(await readError(res));
    return res.json();
}

async function askImages(files, question) {
    const fd = new FormData();
    files.forEach(f => fd.append('files', f));     // 字段名统一叫 files(可重复)
    if (question) fd.append('question', question);
    // 注意:用 FormData 时千万不要自己设 Content-Type,
    //       浏览器会自动带上 multipart 的 boundary
    const res = await fetch('/api/agent', { method: 'POST', body: fd });
    if (!res.ok) throw new Error(await readError(res));
    return res.json();
}

/* ---------------- 知识库概览 ---------------- */

async function loadKnowledge() {
    const box = document.getElementById('kb');
    try {
        const data = await (await fetch('/api/knowledge?sample=5')).json();
        const sources = (data.sources || []).map(escapeHtml).join('、') || '(空)';
        const sample = (data.sample || []).map(s => `<li>${escapeHtml(s)}</li>`).join('');
        box.innerHTML = `<div class="stat"><b>${data.count}</b> 条知识点</div>
                         <div class="muted small">来源:${sources}</div>
                         <ul class="sample">${sample}</ul>`;
    } catch (e) {
        box.textContent = '加载失败:' + e.message;
    }
}

loadKnowledge();
