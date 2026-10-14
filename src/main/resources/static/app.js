/* ---------------------------------------------------------------------
   Talk to Textbook 前端逻辑(原生 JS,无框架)

   它只干三件事:
     1. 把用户输入(文字 / 一批图片)塞进 HTTP 请求
     2. 把响应渲染成气泡 —— 文字提问走 SSE 流式(边生成边显示,可中途停止),
        图片提问仍走老的一次性接口
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
const stopBtn = document.getElementById('stop');
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

/** 正在进行的流式请求:非 null 就说明"正在生成",用它来防重复提交 / 实现停止。 */
let streamCtrl = null;

/* ---------------- 通用对话框(重命名 / 删除确认) ----------------

   以前直接用 window.prompt / window.confirm —— 样式完全不能控,跟页面两个画风。
   这里做一个小对话框:白底卡片 + 主题蓝按钮(破坏性操作用红)。
   回车 = 确定、Esc / 点灰底 / 关闭 = 取消。两种用法:

     要用户填字:const name = await uiDialog({ title, input: '原名字', okText: '保存' })
                 → 返回字符串(确定)或 null(取消 / 关掉)
     只确认一下:const ok   = await uiDialog({ title, text, okText: '删除', danger: true })
                 → 返回 true / false
   ------------------------------------------------------------------ */

const uiModal = document.getElementById('uiModal');
const uiTitleEl = document.getElementById('uiTitle');
const uiTextEl = document.getElementById('uiText');
const uiInputEl = document.getElementById('uiInput');
const uiOkBtn = document.getElementById('uiOk');
const uiCancelBtn = document.getElementById('uiCancel');
const uiCloseBtn = document.getElementById('uiClose');

/** 当前这次对话的"收尾":确定/取消/Esc/点灰底都走它 —— 保证 Promise 只结算一次。 */
let uiSettle = null;

function uiDialog({ title, text = '', input = null, okText = '确定', danger = false }) {
    uiTitleEl.textContent = title;
    uiTextEl.textContent = text;
    uiTextEl.hidden = !text;
    uiInputEl.hidden = input === null;
    uiInputEl.value = input ?? '';
    uiOkBtn.textContent = okText;
    uiOkBtn.classList.toggle('danger', !!danger);
    uiModal.hidden = false;
    if (input !== null) { uiInputEl.focus(); uiInputEl.select(); }

    return new Promise(resolve => {
        uiSettle = (ok) => {
            uiSettle = null;
            uiModal.hidden = true;
            resolve(ok ? (input !== null ? uiInputEl.value.trim() : true)
                       : (input !== null ? null : false));
        };
    });
}

function uiClose(ok) { if (uiSettle) uiSettle(ok); }

uiOkBtn.addEventListener('click', () => uiClose(true));
uiCancelBtn.addEventListener('click', () => uiClose(false));
uiCloseBtn.addEventListener('click', () => uiClose(false));
uiModal.addEventListener('click', (e) => { if (e.target === uiModal) uiClose(false); });   // 点灰底 = 取消
uiInputEl.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); uiClose(true); } });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !uiModal.hidden) uiClose(false); });

/* ---------------- 会话状态 ---------------- */

/** 当前会话 id:存在 localStorage,刷新页面还停在原来那个框 */
const CONV_STORAGE_KEY = 'fa.conversationId';
let currentConversationId = localStorage.getItem(CONV_STORAGE_KEY);
let defaultConversationId = 'default';

/* ---- 📝 存笔记(勾选模式)状态 ---- */
let noteSelectMode = false;          // 是否正处在「勾选提问」模式
let noteSelected = new Set();        // 勾中的消息下标(只可能是 role=user 的)
let noteSavedThisRound = false;      // 这一轮勾选里已经保存过了吗(决定退出时要不要问)

const GREETING = '你好!我是教材问答助手。直接提问,或者上传题目图片(一次最多 10 张)—— 我会自己去知识库里查资料,再回答你。每个对话各自独立记忆,想另起一个话题就点左上角「＋ 新建」。';

/* ---------------- 小工具 ---------------- */

function escapeHtml(s) {
    return (s ?? '').replace(/[&<>"']/g,
        c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** 兜底显示(不渲染 Markdown/公式时的"老样子"):纯文本 + 换行。 */
function plainTextHtml(src) {
    return escapeHtml(String(src ?? '')).replace(/\n/g, '<br>');
}

function addBubble(className, html) {
    const div = document.createElement('div');
    div.className = 'bubble ' + className;
    div.innerHTML = html;
    messagesEl.appendChild(div);
    scrollToBottom(true);
    return div;
}

/**
 * 滚到底部。
 *
 * `force = false`(流式边生成边滚时用)只在**人本来就贴着底部**时才滚 ——
 * 否则你正往回翻历史,一条 delta 就把你拽回最底下,什么也看不成。
 */
function scrollToBottom(force) {
    const slack = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight;
    if (force || slack < 80) messagesEl.scrollTop = messagesEl.scrollHeight;
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

/** 生成中:藏起"提问"、亮出"停止",防止重复提交。 */
function setStreaming(on) {
    sendBtn.disabled = on;
    stopBtn.hidden = !on;
}

/* ---------------- 数学公式 + Markdown 渲染 ----------------

   模型是按 Markdown + LaTeX 输出的(`**粗体**`、`- 列表`、`$$\Delta\varphi=...$$` 都是)。
   浏览器不认这两样,直接显示就满屏 `**` 和 `$$` —— 这里把它们渲染成真的排版。

   流水线:抠公式 → 转义 → Markdown → 把公式塞回去(交给 KaTeX 渲染)。
   ⚠️ 顺序不能换:公式里 `(\omega_2-\omega_1)` 的下划线会被 Markdown 当成 *斜体*,
      `\varphi` 的反斜杠会被当转义符 —— 必须先把公式抠出来换成占位符,
      等 Markdown 跑完再把占位符换回来。
   --------------------------------------------------------------------- */

const MATH_TOKEN = '@@MATH';
const MATH_DELIMITERS = [
    { open: '$$', close: '$$', display: true },      // 块级公式(独占一行)
    { open: '\\[', close: '\\]', display: true },   // 块级(LaTeX 原生写法)
    { open: '\\(', close: '\\)', display: false },  // 行内
    { open: '$', close: '$', display: false },       // 行内(最常用)
];

/** `$` 太容易误伤(价格、货币),得比 `$$` 多守两道门。 */
function looksLikeMath(text, open, close) {
    if (open !== '$') return true;
    const afterOpen = text[open.length];
    return afterOpen !== undefined && !/\s/.test(afterOpen);   // `$ 5` 不是公式
}

/** 找到合法的收尾位置:`$` 前面不能是空白、后面不能紧跟数字。 */
function findMathEnd(text, from, close) {
    let at = from;
    while (true) {
        const idx = text.indexOf(close, at);
        if (idx < 0) return -1;
        if (close !== '$') return idx;
        const before = text[idx - 1];
        const after = text[idx + 1];
        if (before !== undefined && !/\s/.test(before) && !/^[0-9]/.test(after || ' ')) return idx;
        at = idx + 1;
    }
}

/** 把公式抠成占位符,返回剩下的文本 + 公式清单(含 KaTeX 源码与原文,便于回退)。 */
function extractMath(src) {
    const found = [];
    let out = '';
    let i = 0;
    while (i < src.length) {
        const hit = MATH_DELIMITERS.find(d => src.startsWith(d.open, i)
            && !(i > 0 && src[i - 1] === '\\')              // `\$`、`\\(` 是转义,不是公式
            && looksLikeMath(src.slice(i), d.open, d.close));
        if (!hit) { out += src[i++]; continue; }

        const bodyStart = i + hit.open.length;
        const end = findMathEnd(src, bodyStart, hit.close);
        if (end < 0 || end === bodyStart) { out += src[i++]; continue; }   // 没闭合 / 空公式 → 当普通字符

        out += MATH_TOKEN + found.length + '@@';
        found.push({
            tex: src.slice(bodyStart, end),
            display: hit.display,
            raw: src.slice(i, end + hit.close.length),
        });
        i = end + hit.close.length;
    }
    return { text: out, found };
}

/** 交给 KaTeX;渲染不了就返回 null(由调用方回退显示原文,总比一片红好)。 */
function katexHtml(tex, display) {
    try {
        return window.katex.renderToString(tex, {
            displayMode: display,
            throwOnError: true,      // 抛出来才好回退到原文
            trust: false,            // 不执行 \\htmlClass 之类的危险指令
            strict: false,
        });
    } catch (e) {
        return null;
    }
}

/**
 * 把代码块 / 行内代码先掩起来。
 *
 * 不是为了渲染 —— 是为了 **别误伤**:
 *   ① ``` 里的 `$$...$$` 是例子,不是公式;
 *   ② `$PATH` 这种行内代码不该被当成公式。
 * 抿完之后立刻恢复原文(交给 Markdown 正常处理代码块),只是「挖公式」那一步看不见它们。
 */
function protectCode(src) {
    const parts = [];
    const text = src.replace(/```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`/g, m => {
        parts.push(m);
        return '@@CODE' + (parts.length - 1) + '@@';
    });
    return { text, parts };
}

function restoreCode(text, parts) {
    return text.replace(/@@CODE(\d+)@@/g, (whole, n) => parts[Number(n)] ?? whole);
}

/**
 * Markdown 解析前的转义:**只挡 `<` 和 `&`**。
 * 挡住 `<` 就够把标签堵死(`<script>`、`<img onerror>` 都得从 `<` 开始);
 * 而 `>` 必须留着 —— 否则 `> 引用` 就不是引用块了。
 */
function escapeForMarkdown(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;');
}

/** 一段模型输出(含 Markdown + LaTeX)→ 安全可插入的 HTML。 */
function renderRichText(raw) {
    const src = String(raw ?? '');
    if (!src.trim()) return '<p>(空回答)</p>';
    if (!window.marked || !window.katex) return plainTextHtml(src);   // 库没加载上(缓存/文件缺)→ 退化成纯文本,别把回答弄丢

    try {
        const code = protectCode(src);                        // ① 代码块先拿到一边
        const { text, found } = extractMath(code.text);       // ② 抠公式
        const markdownSrc = escapeForMarkdown(restoreCode(text, code.parts));   // ③ 代码放回 + 转义
        let html = window.marked.parse(markdownSrc, { breaks: true, gfm: true });   // ④ Markdown

        // 块级公式会被 Markdown 裹上一层 <p>,拆掉这层壳(katex-display 本身就是块级元素)
        found.forEach((m, i) => {
            if (!m.display) return;
            const token = MATH_TOKEN + i + '@@';
            html = html.replace(new RegExp('<p>\\s*' + token + '\\s*</p>', 'g'), token);
        });

        return html.replace(new RegExp(MATH_TOKEN + '(\\d+)@@', 'g'), (whole, n) => {   // ⑤ 公式换回真排版
            const m = found[Number(n)];
            if (!m) return whole;
            const rendered = katexHtml(m.tex, m.display);
            return rendered !== null ? rendered : escapeHtml(m.raw);
        });
    } catch (e) {
        // 渲染只是"锦上添花":它炸了不能把答案一起炸掉 → 退回纯文本
        console.warn('[renderRichText] 渲染失败,退化成纯文本', e);
        return plainTextHtml(src);
    }
}

/* ---------------- 渲染回答(含工具轨迹) ---------------- */

function renderAnswer(res) {
    const answer = renderRichText(res.answer || '(空回答)');

    let trace = '<div class="muted small">(这题不需要查资料,直接回答)</div>';
    if (res.trace && res.trace.length) {
        const items = res.trace.map(t => `<li><code>${escapeHtml(t)}</code></li>`).join('');
        trace = `<details class="trace"><summary>它调用了 ${res.trace.length} 次工具(共 ${res.steps} 步)</summary>
                     <ol>${items}</ol></details>`;
    }
    return `<div class="answer rich">${answer}</div>${trace}`;
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

        const badge = [];
        if (c.scopeId && c.scopeId !== c.id) {         // 长期记忆正和别的对话共用
            const share = document.createElement('span');
            share.className = 'conv-share';
            share.textContent = '🔗';
            share.title = '长期记忆和「' + c.scopeId + '」共用';
            badge.push(share);
        }
        item.append(title, ...badge, tools);
        convListEl.appendChild(item);
    });
}

/** 切到另一个对话:先记住,再把它的聊天记录画出来。 */
async function switchConversation(id) {
    if (noteSelectMode) await exitNoteSelect(false);   // 切对话也算"退出勾选",没保存会问一句
    if (id === currentConversationId) return;
    currentConversationId = id;
    localStorage.setItem(CONV_STORAGE_KEY, id);
    await loadConversations(id);
    await loadThread(id);
}

function renderStoredMessage(m, index) {
    if (m.role === 'user') {
        addUserBubble(m.content, index);          // 存笔记模式下会带上勾选框
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
        msgs.forEach((m, i) => renderStoredMessage(m, i));
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
    const name = await uiDialog({
        title: '重命名对话',
        input: conv.title || '',
        okText: '保存'
    });
    if (name === null) return;                  // 取消 / 关掉 / 点灰底
    if (!name) return;                          // 空的就不改(别把名字清没了)
    if (name === conv.title) return;            // 名字没变,省一次请求
    try {
        await apiJson('/api/conversations/' + encodeURIComponent(conv.id), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: name })
        });
        await loadConversations(conv.id);
    } catch (e) {
        window.alert('重命名失败:' + e.message);
    }
}

async function deleteConversation(conv) {
    const ok = await uiDialog({
        title: '删除对话',
        text: `「${conv.title || ''}」里的聊天记录会跟着一起删掉,删了拿不回来。`,
        okText: '删除',
        danger: true
    });
    if (!ok) return;
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
    if (noteSelectMode) { setHint('先退出「存笔记」模式,再提问'); return; }
    if (streamCtrl) return;                            // 正在生成,别叠着发
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

    try {
        if (filesToSend.length) {
            await answerByImages(filesToSend, question);    // 图片要先逐张识别,仍走老接口
        } else if (!window.ReadableStream || !window.TextDecoder) {
            await answerByText(question);                   // 浏览器太老,退化成一次性返回
        } else {
            await answerByStream(question);
        }
    } finally {
        loadConversations(currentConversationId);      // 首条提问会自动改标题,刷新一下列表
    }
});

/* ---------------- 流式问答(SSE) ---------------- */

/** 把一段 SSE 报文(两个空行之间)解析成 { name, data }。 */
function parseSseEvent(raw) {
    let name = 'message';
    const dataLines = [];
    for (const line of raw.split('\n')) {
        if (!line || line.startsWith(':')) continue;       // ": ping" 是心跳注释,忽略
        const colon = line.indexOf(':');
        const field = colon === -1 ? line : line.slice(0, colon);
        let value = colon === -1 ? '' : line.slice(colon + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'event') name = value;
        else if (field === 'data') dataLines.push(value);
    }
    if (!dataLines.length) return null;
    const text = dataLines.join('\n');
    try {
        return { name, data: JSON.parse(text) };
    } catch {
        return { name, data: { text } };
    }
}

/** 逐块读响应体,按空行切出事件,喂给 onEvent(name, data)。 */
async function readSse(body, onEvent) {
    const reader = body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buf = '';
    for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
        let idx;
        while ((idx = buf.indexOf('\n\n')) >= 0) {
            const ev = parseSseEvent(buf.slice(0, idx));
            buf = buf.slice(idx + 2);
            if (ev) onEvent(ev.name, ev.data);
        }
    }
    if (buf.trim()) {                                   // 收尾那一条可能没跟空行
        const ev = parseSseEvent(buf);
        if (ev) onEvent(ev.name, ev.data);
    }
}

/* ---- 流式增量渲染:别让人一路看着裸的 `**` 和 `$$` ---- */

const STREAM_RENDER_THROTTLE_MS = 150;   // 两次重画之间最少隔这么久
const STREAM_RENDER_MIN_CHARS = 12;      // 至少又长了这么多字才值得重画一遍

function countOf(text, needle) { return text.split(needle).length - 1; }

/**
 * 现在这段文字能不能安全地渲染?
 *
 * 只守一件事:**别渲染"半截"的块级结构** —— 没闭合的代码块、没闭合的块级公式
 * 会被 Markdown 当成普通文字,把整段排版弄乱。宁可先不重画(继续显示纯文本),
 * 等它闭合了再重画。行内公式(单 `$`)半截没关系:抠不出来就当普通字符显示。
 */
function safeToRenderPartial(text) {
    if (countOf(text, '```') % 2 !== 0) return false;                        // 代码块没闭合
    if (countOf(text, '$$') % 2 !== 0) return false;                         // 块级公式没闭合
    if (countOf(text, '\\[') !== countOf(text, '\\]')) return false;         // \[ 与 \] 不成对
    return true;
}

/**
 * 流式提问:答案边生成边显示,可中途"停止"。
 *
 * 事件:status(进度) / reset(刚才那半句作废) / delta(新正文) / done(最终答案) / error。
 *
 * 正文处理三段式:
 *   ① 每来一条 delta 先按**纯文本**贴上 —— 保证"看得见字在长",不卡顿;
 *   ② 节流(150ms)+ 安全检查后,把已有的文字**重画成排版好的 HTML**(公式、列表都当场成型);
 *   ③ 收到 done(或停止/断线)再整体重画一遍 —— 终稿一定是最准的。
 */
async function answerByStream(question) {
    const bubble = addBubble('bot',
        '<div class="answer"></div><div class="muted small stream-status">正在思考…</div>');
    const answerEl = bubble.querySelector('.answer');
    const statusEl = bubble.querySelector('.stream-status');

    const ctrl = new AbortController();
    streamCtrl = ctrl;
    setStreaming(true);

    let done = null;
    let failure = null;
    let answerText = '';                 // 已收到的正文(原样,未渲染)
    let renderedLen = 0;                 // 上次重画时的长度(用来节流)
    let renderTimer = null;

    const cancelRender = () => {
        if (renderTimer !== null) { clearTimeout(renderTimer); renderTimer = null; }
    };

    /** 把当前已收到的正文重画成排版好的 HTML(不满足条件就什么都不做)。 */
    const flushRender = () => {
        renderTimer = null;
        if (!window.marked || !window.katex) return;               // 库没加载上就老实显示纯文本
        if (answerText.length - renderedLen < STREAM_RENDER_MIN_CHARS
                && !answerText.endsWith('\n')) return;             // 才长了几个字,不值得重画
        if (!safeToRenderPartial(answerText)) return;              // 半截的代码块/公式,等闭合
        try {
            answerEl.classList.add('rich');
            answerEl.innerHTML = renderRichText(answerText);
            renderedLen = answerText.length;
            scrollToBottom();
        } catch (e) {
            console.warn('流式重画失败,继续用纯文本', e);
            renderedLen = answerText.length;                       // 别在同一段上反复撞
        }
    };

    const scheduleRender = () => {
        if (renderTimer === null) renderTimer = setTimeout(flushRender, STREAM_RENDER_THROTTLE_MS);
    };

    try {
        const res = await fetch('/api/agent/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question, conversationId: currentConversationId }),
            signal: ctrl.signal
        });
        if (!res.ok) throw new Error(await readError(res));

        await readSse(res.body, (name, data) => {
            switch (name) {
                case 'delta':
                    answerText += (data.text || '');
                    answerEl.textContent = answerText;          // ① 先把字贴上
                    statusEl.textContent = '';
                    statusEl.classList.add('done');
                    scheduleRender();                          // ② 再考虑重画成排版
                    break;
                case 'reset':                       // 那一轮其实是要调工具,吐出来的不算
                    cancelRender();
                    answerText = '';
                    renderedLen = 0;
                    answerEl.textContent = '';
                    answerEl.classList.remove('rich');
                    statusEl.classList.remove('done');
                    break;
                case 'status':
                    statusEl.textContent = data.text || '';
                    break;
                case 'done':
                    done = data;
                    break;
                case 'error':
                    failure = new Error(data.message || '服务端出错');
                    break;
            }
            scrollToBottom();
        });

        if (failure) throw failure;
        cancelRender();                             // 别让迟到的重画盖掉终稿

        bubble.className = 'bubble bot';
        if (done) {
            bubble.innerHTML = renderAnswer(done);      // ③ 用最终答案重画一遍(带工具轨迹)
        } else {
            // 没收到 done 就断了(网络/服务重启):把已生成的部分留着(尽量排版),说清楚就行
            if (answerText) {
                answerEl.classList.add('rich');
                answerEl.innerHTML = renderRichText(answerText);
            }
            statusEl.textContent = '(连接中断,以上是已经生成的部分)';
        }
        scrollToBottom(true);
    } catch (err) {
        cancelRender();
        if (err.name === 'AbortError') {
            if (answerText && window.marked && window.katex) {      // 停下来了也把已有的部分排版一下
                try {
                    answerEl.classList.add('rich');
                    answerEl.innerHTML = renderRichText(answerText);
                } catch (e) { /* 保留半截纯文本 */ }
            }
            statusEl.textContent = '(已停止生成)';
            statusEl.classList.add('done');
            return;                                     // 半截答案留着,不当错误处理
        }
        bubble.className = 'bubble bot error';
        bubble.textContent = '出错了:' + err.message;
        await fallBackToDefaultConversation(err);
    } finally {
        cancelRender();
        streamCtrl = null;
        setStreaming(false);
    }
}

/** 一次性问答(老接口):浏览器不支持流式读取时的退路。 */
async function answerByText(question) {
    const bubble = addBubble('bot thinking', '正在思考…');
    setStreaming(true);
    try {
        const result = await askText(question);
        bubble.className = 'bubble bot';
        bubble.innerHTML = renderAnswer(result);
    } catch (err) {
        bubble.className = 'bubble bot error';
        bubble.textContent = '出错了:' + err.message;
        await fallBackToDefaultConversation(err);
    } finally {
        setStreaming(false);
    }
}

/** 图片问答:逐张识别要花时间,这里用"已等待 Ns"当进度(那条链路还没做流式)。 */
async function answerByImages(filesToSend, question) {
    const bubble = addBubble('bot thinking', '正在思考…');
    const startedAt = Date.now();
    const timer = setInterval(() => {
        const secs = Math.round((Date.now() - startedAt) / 1000);
        bubble.textContent = `正在识别 ${filesToSend.length} 张图…已等待 ${secs}s (大约需要 ${filesToSend.length * 12} 秒)`;
    }, 1000);

    setStreaming(true);
    try {
        const result = await askImages(filesToSend, question);
        bubble.className = 'bubble bot';
        bubble.innerHTML = renderAnswer(result);
    } catch (err) {
        bubble.className = 'bubble bot error';
        bubble.textContent = '出错了:' + err.message;
        await fallBackToDefaultConversation(err);
    } finally {
        clearInterval(timer);
        setStreaming(false);
    }
}

/** 会话在后端被删了(比如另一个标签页删的)-> 悄悄回落到默认对话,别让人卡死。 */
async function fallBackToDefaultConversation(err) {
    if (!String(err && err.message).includes('会话不存在')) return;
    currentConversationId = defaultConversationId;
    localStorage.setItem(CONV_STORAGE_KEY, currentConversationId);
    await loadConversations(currentConversationId);
}

stopBtn.addEventListener('click', () => {
    if (streamCtrl) streamCtrl.abort();      // 断开连接;服务端察觉连接断了会停下
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
const memScopeEl = document.getElementById('memScope');
const memScopeHintEl = document.getElementById('memScopeHint');

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

/**
 * 记忆域:这个对话的长期记忆是“自己一份”,还是跟另一个对话<b>共用</b>一份。
 *
 * 共享 = 指向同一份存档(不做复制同步),所以那边加一条、这边立刻也能看到;
 * 拆开时只是改指向,已共享的那份留在原处(不会把别人的记忆搬走)。
 */
async function loadMemoryScope() {
    let data;
    try {
        data = await apiJson('/api/conversations');
    } catch (e) {
        memScopeEl.innerHTML = '';
        memScopeHintEl.textContent = '读不到会话列表:' + e.message;
        return;
    }

    const me = currentConversationId;
    const convs = data.conversations || [];
    const mine = convs.find(c => c.id === me);
    const scope = (mine && mine.scopeId) || me;

    memScopeEl.innerHTML = '';
    const own = document.createElement('option');
    own.value = '';
    own.textContent = '只属于这个对话(独立记忆)';
    memScopeEl.appendChild(own);

    convs.forEach(c => {
        if (c.id === me) return;
        const opt = document.createElement('option');
        opt.value = c.id;
        opt.textContent = '和「' + (c.title || c.id) + '」共用';
        memScopeEl.appendChild(opt);
    });

    // 指向的那个对话被删了 → 下拉里没这一项,补一个(否则会看着像是“自己恢复独立了”)
    if (scope !== me && !Array.from(memScopeEl.options).some(o => o.value === scope)) {
        const ghost = document.createElement('option');
        ghost.value = scope;
        ghost.textContent = '和已被删掉的「' + scope + '」共用';
        memScopeEl.appendChild(ghost);
    }
    memScopeEl.value = scope === me ? '' : scope;

    memScopeHintEl.textContent = scope === me
        ? '这个对话的记忆只属于它自己。想两个话题共用记忆(比如“物理”和“物理作业”),选一个对话共用即可。'
        : '这份记忆和那个对话是同一份(不是复制)—— 两边加一条、删一条,互相都看得到。';
}

memScopeEl.addEventListener('change', async () => {
    const target = memScopeEl.value;
    try {
        await apiJson('/api/conversations/' + encodeURIComponent(currentConversationId) + '/memory-scope', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ scopeId: target || null })
        });
        await loadConversations();        // 侧栏那条链子标记要跟着变
        await loadMemoryScope();
        await loadMemories();
    } catch (e) {
        window.alert('没改成:' + e.message);
        await loadMemoryScope();
    }
});

function openMemory() {
    memoryModal.hidden = false;
    memTextEl.focus();
    loadMemories();
    loadMemoryScope();
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

/* ---------------- 📝 存笔记 / 📒 笔记本 ----------------

   用法:点「📝 存笔记」→ 消息区里每条「你问过的问题」旁边出现勾选框 →
   勾几个 → 点「保存」→ 存进笔记本;再点「存笔记」= 退出。
   勾了但没保存就退出 → 问一句「要不要保存」。
   ------------------------------------------------------------------ */

const noteBtn = document.getElementById('noteBtn');
const noteSaveBtn = document.getElementById('noteSave');
const openNotebookBtn = document.getElementById('openNotebook');
const notebookModal = document.getElementById('notebookModal');
const closeNotebookBtn = document.getElementById('closeNotebook');
const noteListEl = document.getElementById('noteList');
const noteExportBtn = document.getElementById('noteExport');
const noteModal = document.getElementById('noteModal');
const closeNoteBtn = document.getElementById('closeNote');
const noteDetailEl = document.getElementById('noteDetail');

let currentNoteId = null;

/** 带「可勾选」能力的用户气泡:勾选模式下旁边多一个勾选框。 */
function addUserBubble(text, index) {
    const div = document.createElement('div');
    div.className = 'bubble user';
    if (noteSelectMode && typeof index === 'number') {
        div.classList.add('selectable');
        div.dataset.idx = String(index);
        const box = document.createElement('input');
        box.type = 'checkbox';
        box.className = 'pick-box';
        box.checked = noteSelected.has(index);
        box.addEventListener('change', () => {
            if (box.checked) noteSelected.add(index);
            else noteSelected.delete(index);
            renderNoteButton();
        });
        div.appendChild(box);
    }
    const body = document.createElement('div');
    body.className = 'bubble-body';
    body.innerHTML = escapeHtml(String(text ?? ''));
    div.appendChild(body);
    messagesEl.appendChild(div);
    scrollToBottom(true);
    return div;
}

function renderNoteButton() {
    if (!noteSelectMode) {
        noteBtn.textContent = '📝 存笔记';
        noteBtn.classList.remove('active');
        noteSaveBtn.hidden = true;
        return;
    }
    noteBtn.textContent = noteSelected.size ? `已完成(${noteSelected.size})` : '已完成';
    noteBtn.classList.add('active');
    noteSaveBtn.hidden = noteSelected.size === 0;
    noteSaveBtn.textContent = `保存 (${noteSelected.size})`;
}

async function toggleNoteSelect() {
    if (noteSelectMode) { await exitNoteSelect(true); return; }
    if (streamCtrl) { setHint('等这次回答生成完,再存笔记'); return; }
    noteSelectMode = true;
    noteSelected = new Set();
    noteSavedThisRound = false;
    await loadThread(currentConversationId);      // 从服务端重画 → 消息下标绝对准确
    renderNoteButton();
    setHint('勾选你想存下来的提问,再点「保存」;再点「存笔记」= 退出');
}

/**
 * 退出勾选模式。
 *
 * @param askWhenDirty 勾了但没保存 → 弹框问一句「要不要保存」
 */
async function exitNoteSelect(askWhenDirty) {
    if (!noteSelectMode) return;
    if (askWhenDirty && noteSelected.size > 0 && !noteSavedThisRound) {
        const save = await uiDialog({
            title: '还没保存',
            text: `你还勾着 ${noteSelected.size} 个问题没有保存。点「确定」存进笔记本;点「取消」= 不保存退出。`,
            okText: '保存'
        });
        if (save) await saveNotes();
    }
    noteSelectMode = false;
    noteSelected = new Set();
    noteSavedThisRound = false;
    await loadThread(currentConversationId);
    renderNoteButton();
}

async function saveNotes() {
    const indexes = Array.from(noteSelected).sort((a, b) => a - b);
    if (!indexes.length) return;
    try {
        const res = await apiJson('/api/notes', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ conversationId: currentConversationId, indexes })
        });
        noteSavedThisRound = true;
        setHint(`已存入笔记本 ${res.savedCount} 条`
            + (res.skippedCount ? `(跳过重复 ${res.skippedCount} 条)` : ''));
    } catch (e) {
        window.alert('存笔记失败:' + e.message);
    }
}

async function openNotebook() {
    notebookModal.hidden = false;
    noteListEl.innerHTML = '加载中…';
    try {
        const data = await apiJson('/api/notes');
        renderNoteList(data.notes || []);
    } catch (e) {
        noteListEl.innerHTML = '<p class="muted small">读不出来:' + escapeHtml(e.message) + '</p>';
    }
}

function closeNotebook() { notebookModal.hidden = true; }

function renderNoteList(notes) {
    noteListEl.innerHTML = '';
    if (!notes.length) {
        noteListEl.innerHTML = '<p class="muted small">还没有笔记。在对话里点「📝 存笔记」勾几个问题试试。</p>';
        return;
    }
    notes.forEach(n => {
        const row = document.createElement('div');
        row.className = 'note-row';

        const open = document.createElement('button');
        open.type = 'button';
        open.className = 'note-open';
        open.innerHTML = `<span class="note-q">${escapeHtml(n.question)}</span>`
            + `<span class="note-meta muted small">${n.answerCount} 条回答 · ${formatTime(n.savedAt)}</span>`;
        open.addEventListener('click', () => openNote(n.id));

        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'note-del';
        del.title = '删除这条笔记';
        del.setAttribute('aria-label', '删除');
        del.textContent = '×';
        del.addEventListener('click', (e) => {
            e.stopPropagation();
            deleteNoteById(n.id);
        });

        row.appendChild(open);
        row.appendChild(del);
        noteListEl.appendChild(row);
    });
}

async function openNote(id) {
    noteModal.hidden = false;
    noteDetailEl.innerHTML = '加载中…';
    currentNoteId = id;
    try {
        const n = await apiJson('/api/notes/' + encodeURIComponent(id));
        const answers = (n.answers || []).map(a =>
            '<div class="note-answer">'
            + renderAnswer({ answer: a.text, trace: a.trace, steps: (a.trace || []).length })
            + `<div class="muted small">${formatTime(a.at)}</div></div>`).join('');
        noteDetailEl.innerHTML =
            `<h3 class="note-q-h">📌 ${escapeHtml(n.question)}</h3>`
            + `<p class="muted small">存于 ${formatTime(n.savedAt)}</p>`
            + (answers || '<p class="muted small">这条问题当时还没有回答。</p>');
    } catch (e) {
        noteDetailEl.innerHTML = '<p class="muted small">读不出来:' + escapeHtml(e.message) + '</p>';
    }
}

function closeNote() { noteModal.hidden = true; }

async function deleteNoteById(id) {
    const ok = await uiDialog({ title: '删除笔记', text: '删了就真没了。', okText: '删除', danger: true });
    if (!ok) return;
    try {
        await apiJson('/api/notes/' + encodeURIComponent(id), { method: 'DELETE' });
        if (currentNoteId === id) closeNote();      // 正开着它的详情就一并关掉
        await openNotebook();                       // 列表立即刷新
    } catch (e) {
        window.alert('删除失败:' + e.message);
    }
}

noteBtn.addEventListener('click', toggleNoteSelect);
noteSaveBtn.addEventListener('click', async () => { await saveNotes(); await exitNoteSelect(false); });
openNotebookBtn.addEventListener('click', openNotebook);
closeNotebookBtn.addEventListener('click', closeNotebook);
notebookModal.addEventListener('click', (e) => { if (e.target === notebookModal) closeNotebook(); });
closeNoteBtn.addEventListener('click', closeNote);
noteModal.addEventListener('click', (e) => { if (e.target === noteModal) closeNote(); });
noteExportBtn.addEventListener('click', () => { window.location = '/api/notes/export'; });   // 后端带 Content-Disposition,直接当附件下载
document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!noteModal.hidden) closeNote();
    else if (!notebookModal.hidden) closeNotebook();
});

loadKnowledge();
initConversations();
