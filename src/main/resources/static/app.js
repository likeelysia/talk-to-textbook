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
const convListEl = document.getElementById('convList');
const newConvBtn = document.getElementById('newConv');

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

/* ---------------- 会话状态 ---------------- */

/** 当前会话 id:存在 localStorage,刷新页面还停在原来那个框 */
const CONV_STORAGE_KEY = 'fa.conversationId';
let currentConversationId = localStorage.getItem(CONV_STORAGE_KEY);
let defaultConversationId = 'default';

const GREETING = '你好!我是教材问答助手。直接提问,或者上传题目图片(一次最多 10 张)—— 我会自己去知识库里查资料,再回答你。每个对话各自独立记忆,想另起一个话题就点左上角「＋ 新建」。';

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

/* ---------------- 会话(对话框) ---------------- */

/** 统一的 JSON 请求:非 2xx 就把后端给的 {"error":"..."} 抛出来。 */
async function apiJson(url, options) {
    const res = await fetch(url, options);
    if (!res.ok) throw new Error(await readError(res));
    return res.json();
}

/** 拉会话列表;preferredId 不存在(比如删掉了)就回落到默认会话。 */
async function loadConversations(preferredId) {
    let data;
    try {
        data = await apiJson('/api/conversations');
    } catch (e) {
        convListEl.textContent = '加载失败:' + e.message;
        return;
    }
    const convs = data.conversations || [];
    defaultConversationId = data.defaultId || 'default';

    const want = preferredId || currentConversationId;
    currentConversationId = convs.some(c => c.id === want) ? want : defaultConversationId;
    localStorage.setItem(CONV_STORAGE_KEY, currentConversationId);

    renderConversationList(convs);
}

function renderConversationList(convs) {
    convListEl.innerHTML = '';
    convs.forEach(c => {
        const item = document.createElement('div');
        item.className = 'conv-item' + (c.id === currentConversationId ? ' active' : '');

        // 用 textContent 而不是 innerHTML —— 标题是用户自己输入的,不能当 HTML 解析(XSS)
        const title = document.createElement('button');
        title.type = 'button';
        title.className = 'conv-title';
        title.textContent = c.title || '(无标题)';
        title.title = `${c.title || ''}(${c.messageCount} 条)`;
        title.addEventListener('click', () => switchConversation(c.id));

        const tools = document.createElement('span');
        tools.className = 'conv-tools';

        const rename = document.createElement('button');
        rename.type = 'button';
        rename.className = 'mini';
        rename.textContent = '✎';
        rename.title = '重命名';
        rename.addEventListener('click', (e) => { e.stopPropagation(); renameConversation(c); });
        tools.append(rename);

        if (c.id !== defaultConversationId) {          // 默认对话留着兜底,不给删
            const del = document.createElement('button');
            del.type = 'button';
            del.className = 'mini danger';
            del.textContent = '×';
            del.title = '删除这个对话';
            del.addEventListener('click', (e) => { e.stopPropagation(); deleteConversation(c); });
            tools.append(del);
        }

        item.append(title, tools);
        convListEl.appendChild(item);
    });
}

/** 切到另一个对话:先记住,再把它的聊天记录画出来。 */
async function switchConversation(id) {
    if (id === currentConversationId) return;
    currentConversationId = id;
    localStorage.setItem(CONV_STORAGE_KEY, id);
    await loadConversations(id);
    await loadThread(id);
}

function renderStoredMessage(m) {
    if (m.role === 'user') {
        addBubble('user', `<div>${escapeHtml(m.content)}</div>`);
    } else if (m.role === 'assistant') {
        const trace = m.trace || [];
        addBubble('bot', renderAnswer({ answer: m.content, trace, steps: trace.length }));
    }
}

/** 读某个对话的历史,画进消息区(空对话就显示欢迎语)。 */
async function loadThread(id) {
    let conv;
    try {
        conv = await apiJson('/api/conversations/' + encodeURIComponent(id));
    } catch (e) {
        messagesEl.innerHTML = '';
        addBubble('bot error', '聊天记录读不出来:' + escapeHtml(e.message));
        return;
    }

    messagesEl.innerHTML = '';
    const msgs = conv.messages || [];
    if (!msgs.length) {
        addBubble('bot', GREETING);
    } else {
        msgs.forEach(renderStoredMessage);
    }
    loadMemoryCount();                 // 顺手把这个对话“记住了几条”刷到按钮上
}

async function newConversation() {
    try {
        const created = await apiJson('/api/conversations', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({})
        });
        currentConversationId = created.id;
        localStorage.setItem(CONV_STORAGE_KEY, created.id);
        await loadConversations(created.id);
        await loadThread(created.id);
        questionEl.focus();
    } catch (e) {
        window.alert('新建对话失败:' + e.message);
    }
}

async function renameConversation(conv) {
    const name = window.prompt('重命名这个对话:', conv.title || '');
    if (name === null) return;                        // 点了取消
    const title = name.trim();
    if (!title) return;
    try {
        await apiJson('/api/conversations/' + encodeURIComponent(conv.id), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title })
        });
        await loadConversations(conv.id);
    } catch (e) {
        window.alert('重命名失败:' + e.message);
    }
}

async function deleteConversation(conv) {
    if (!window.confirm(`删除对话「${conv.title || ''}」?里面的聊天记录会跟着删掉。`)) return;
    try {
        await apiJson('/api/conversations/' + encodeURIComponent(conv.id), { method: 'DELETE' });
        if (conv.id === currentConversationId) {
            currentConversationId = defaultConversationId;
        }
        await loadConversations(currentConversationId);
        await loadThread(currentConversationId);
    } catch (e) {
        window.alert('删除失败:' + e.message);
    }
}

newConvBtn.addEventListener('click', newConversation);

/** 首次进页面:先拿列表(决定停在哪个对话),再画它的记录。 */
async function initConversations() {
    await loadConversations();
    await loadThread(currentConversationId);
}

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
        // 会话在后端被删了(比如另一个标签页删的)-> 悄悄回落到默认对话,别让人卡死
        if (String(err.message).includes('会话不存在')) {
            currentConversationId = defaultConversationId;
            localStorage.setItem(CONV_STORAGE_KEY, currentConversationId);
            await loadConversations(currentConversationId);
        }
    } finally {
        clearInterval(timer);
        sendBtn.disabled = false;
        loadConversations(currentConversationId);      // 首条提问会自动改标题,刷新一下列表
    }
});

/* ---------------- 两个接口调用 ---------------- */

async function askText(question) {
    const res = await fetch('/api/agent', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, conversationId: currentConversationId })
    });
    if (!res.ok) throw new Error(await readError(res));
    return res.json();
}

async function askImages(files, question) {
    const fd = new FormData();
    files.forEach(f => fd.append('files', f));     // 字段名统一叫 files(可重复)
    if (question) fd.append('question', question);
    fd.append('conversationId', currentConversationId);   // 记到当前这个框里
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

/* ---------------- 长期记忆:看得见、改得动、删得掉 ---------------- */

const openMemoryBtn = document.getElementById('openMemory');
const memoryModal = document.getElementById('memoryModal');
const closeMemoryBtn = document.getElementById('closeMemory');
const memTextEl = document.getElementById('memText');
const memAddBtn = document.getElementById('memAdd');
const memListEl = document.getElementById('memList');
const memExportBtn = document.getElementById('memExport');
const memForgetBtn = document.getElementById('memForgetAll');

/** 记忆接口都要带上“当前会话”(后端按它找记忆域)。 */
function memQuery() {
    return 'conversationId=' + encodeURIComponent(currentConversationId || '');
}

function formatTime(ms) {
    if (!ms) return '';
    const d = new Date(ms);
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 按钮上那个条数:加载失败也不打扰用户,静静退成不带数字的样子。 */
async function loadMemoryCount() {
    try {
        const data = await apiJson('/api/memory?' + memQuery());
        openMemoryBtn.textContent = `🧠 记忆(${data.count || 0})`;
    } catch {
        openMemoryBtn.textContent = '🧠 记忆';
    }
}

function renderMemories(list) {
    memListEl.innerHTML = '';
    if (!list.length) {
        memListEl.innerHTML = '<p class="muted small">这个对话还没记住什么。</p>';
        return;
    }
    list.forEach(m => {
        const row = document.createElement('div');
        row.className = 'mem-item';

        const pin = document.createElement('button');
        pin.type = 'button';
        pin.className = 'mini' + (m.pinned ? ' pinned' : '');
        pin.textContent = m.pinned ? '📌' : '📍';
        pin.title = m.pinned ? '已钉住(不会被自动清理)—— 点一下取消' : '点一下钉住(不会被自动清理)';
        pin.addEventListener('click', () => togglePin(m));

        // 记忆正文是用户/模型产出的自由文本 —— 一律 textContent,绝不当 HTML 解析(XSS)
        const text = document.createElement('span');
        text.className = 'mem-text';
        text.textContent = m.text;
        text.title = `${m.origin === 'user' ? '你亲手记的' : '助手提炼的'} · ${formatTime(m.createdAt)}`;

        const edit = document.createElement('button');
        edit.type = 'button';
        edit.className = 'mini';
        edit.textContent = '✎';
        edit.title = '改一下';
        edit.addEventListener('click', () => editMemory(m));

        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'mini danger';
        del.textContent = '×';
        del.title = '删掉这条';
        del.addEventListener('click', () => deleteMemory(m));

        const tools = document.createElement('span');
        tools.className = 'mem-tools';
        tools.append(edit, del);

        row.append(pin, text, tools);
        memListEl.appendChild(row);
    });
}

async function loadMemories() {
    memListEl.textContent = '加载中…';
    try {
        const data = await apiJson('/api/memory?' + memQuery());
        renderMemories(data.memories || []);
        openMemoryBtn.textContent = `🧠 记忆(${data.count || 0})`;
    } catch (e) {
        memListEl.textContent = '读不出来:' + e.message;
    }
}

async function addMemory() {
    const text = memTextEl.value.trim();
    if (!text) return;
    try {
        await apiJson('/api/memory', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ conversationId: currentConversationId, text })
        });
        memTextEl.value = '';
        await loadMemories();
    } catch (e) {
        window.alert('没记上:' + e.message);
    }
}

async function patchMemory(m, body) {
    try {
        await apiJson('/api/memory/' + encodeURIComponent(m.id), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(Object.assign({ conversationId: currentConversationId }, body))
        });
        await loadMemories();
    } catch (e) {
        window.alert('没改成:' + e.message);
    }
}

function togglePin(m) {
    return patchMemory(m, { pinned: !m.pinned });
}

function editMemory(m) {
    const text = window.prompt('改成:', m.text || '');
    if (text === null) return;                    // 点了取消
    const clean = text.trim();
    if (!clean || clean === m.text) return;
    return patchMemory(m, { text: clean, pinned: m.pinned });
}

async function deleteMemory(m) {
    if (!window.confirm('删掉这条记忆?')) return;
    try {
        await apiJson('/api/memory/' + encodeURIComponent(m.id) + '?' + memQuery(), { method: 'DELETE' });
        await loadMemories();
    } catch (e) {
        window.alert('没删掉:' + e.message);
    }
}

function openMemory() {
    memoryModal.hidden = false;
    memTextEl.focus();
    loadMemories();
}

function closeMemory() {
    memoryModal.hidden = true;
}

openMemoryBtn.addEventListener('click', openMemory);
closeMemoryBtn.addEventListener('click', closeMemory);
memAddBtn.addEventListener('click', addMemory);
memTextEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); addMemory(); }
});
memoryModal.addEventListener('click', (e) => { if (e.target === memoryModal) closeMemory(); });   // 点遮罩关闭
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !memoryModal.hidden) closeMemory(); });

memExportBtn.addEventListener('click', () => {
    // 后端带了 Content-Disposition,浏览器会当附件直接下载,这里就用最土也最稳的跳转
    window.location = '/api/memory/export?' + memQuery();
});

memForgetBtn.addEventListener('click', async () => {
    if (!window.confirm('忘掉这个对话记住的全部事情?删了就真没了。')) return;
    try {
        await apiJson('/api/memory?' + memQuery(), { method: 'DELETE' });
        await loadMemories();
    } catch (e) {
        window.alert('没清掉:' + e.message);
    }
});

loadKnowledge();
initConversations();
