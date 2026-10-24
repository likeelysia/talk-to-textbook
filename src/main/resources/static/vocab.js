/* 单词手记：以提取快照为准；文章改动/清空使在途响应失效。 */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const ARTICLE_KEY = 'vocab.article.v1';
  const state = { view: 'article', words: [], book: [], selected: new Set(), contexts: new Map(),
    revealed: new Set(), version: 0, snapshot: null, extractTask: null, aiTasks: new Map(), aiErrors: new Map(),
    limit: 40, saving: false, downloading: false, bookLoading: false, bookLoaded: false, serverOffset: 0 };
  let saveTimer, editTarget, deleteTarget, contextTarget;
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const stale = () => state.view === 'article' && state.snapshot !== $('article').value.trim();
  const now = () => Date.now() + state.serverOffset;
  const status = (message, kind = '') => { $('status').textContent = message; $('status').className = kind; };

  async function json(url, options = {}) {
    const response = await authFetch(url, { ...options, headers: {'Content-Type':'application/json', ...(options.headers || {})} });
    if (response.status === 404 && url === '/api/vocab/explain')
      throw new Error('当前运行的后端不支持 AI 解释，请在 IDEA 停止并重新启动项目，再刷新页面重新提取。');
    let data;
    try { data = await response.json(); } catch (_) { throw new Error('服务器没有返回有效结果，请稍后重试'); }
    if (!response.ok) throw new Error(data.error || '请求失败，请稍后重试');
    return data;
  }
  const post = (url, body, extra = {}) => json(url, {method:'POST', body:JSON.stringify(body), ...extra});
  function rows() {
    if (state.view === 'article') return state.words.map(word => ({key:'word:'+word.w, word}));
    return state.book.filter(entry => state.view !== 'review' || entry.dueAt <= now())
      .map(entry => ({key:'book:'+entry.id, word:entry.word, entry}));
  }
  function filtered() {
    const q = $('query').value.trim().toLowerCase(), filter = $('filter').value;
    let list = rows();
    if (q) {
      const exact = list.filter(r => r.word.w.toLowerCase() === q || (r.word.lemma || '').toLowerCase() === q);
      list = exact.length ? exact : list.filter(r => (r.word.w+' '+(r.word.lemma || '')).toLowerCase().includes(q));
    }
    return list.filter(r => filter === 'all' ? true : filter === 'selected' ? state.selected.has(r.key)
      : filter === 'missing' ? !(explanationOf(r)?.cn || r.word.cn) || !(explanationOf(r)?.en || r.word.en)
      : (r.word.tag || '').includes(filter));
  }
  function exportRows() {
    const scope = $('exportScope').value;
    return scope === 'visible' ? filtered() : scope === 'selected' ? rows().filter(r => state.selected.has(r.key)) : rows();
  }
  function contextOf(row) { return state.contexts.get(row.key) || row.word.examples?.[0] || ''; }
  function explanationOf(row) {
    const e = row.word.explanation;
    return e && (!e.context || e.context === contextOf(row)) ? e : null;
  }
  function snapshotWord(row) {
    const context = contextOf(row), explanation = row.word.explanation;
    return {...row.word, examples: context ? [context] : [],
      explanation: explanation && (!explanation.context || explanation.context === context) ? explanation : null};
  }
  function current(row) { return rows().some(r => r.key === row.key && r.word === row.word); }

  function saveArticle() {
    try {
      if ($('article').value) localStorage.setItem(ARTICLE_KEY, $('article').value);
      else localStorage.removeItem(ARTICLE_KEY);
    } catch (_) { /* Browser draft is optional; server notebook has explicit error handling. */ }
  }
  function invalidate() {
    state.version++;
    state.extractTask?.controller.abort();
    state.extractTask = null;
    for (const task of state.aiTasks.values()) task.controller.abort();
    state.aiTasks.clear(); state.aiErrors.clear();
    $('btnExtract').disabled = false;
  }
  function articleChanged() {
    invalidate(); clearTimeout(saveTimer); saveTimer = setTimeout(saveArticle, 300);
    $('charCount').textContent = `${$('article').value.length} / 100000`;
    if (state.words.length) status('文章已修改，请重新提取；旧词表暂时不能保存或导出。');
    render();
  }
  function clearArticle() {
    invalidate(); clearTimeout(saveTimer);
    $('article').value = ''; saveArticle();
    state.words = []; state.snapshot = null; state.selected.clear(); state.contexts.clear();
    $('query').value = ''; $('charCount').textContent = '0 / 100000';
    $('clearDialog').close(); status('文章和提取结果已清空，生词本已保留。'); render(); $('article').focus();
  }

  async function extract() {
    const article = $('article').value.trim();
    if (!article) { status('请先粘贴英文文章。', 'error'); return; }
    if (article.length > 100000) { status('文章超过 100000 字符，请分段处理。', 'error'); return; }
    invalidate(); saveArticle();
    const task = {version:state.version, controller:new AbortController()}; state.extractTask = task;
    $('btnExtract').disabled = true; status('正在提取词条、频次和来源原句…');
    try {
      const data = await post('/api/vocab/extract', {article}, {signal:task.controller.signal});
      if (state.extractTask !== task || task.version !== state.version) return;
      state.words = data.words; state.snapshot = article; state.selected.clear(); state.contexts.clear(); state.limit = 40;
      const missingCn = data.words.filter(w => !w.cn).length, missingEn = data.words.filter(w => !w.en).length;
      status(`提取完成：${data.total} 个不同词条；缺中文 ${missingCn} 个，缺英文 ${missingEn} 个。可勾选生词，或按需补充解释。`, 'success');
      if (data.words.some(w => !Array.isArray(w.examples)))
        status('提取结果没有来源原句：当前运行的后端可能仍是旧版本。请在 IDEA 停止并重新启动项目，刷新页面后重新提取。', 'error');
      render();
    } catch (error) { if (state.extractTask === task && error.name !== 'AbortError') status(error.message, 'error'); }
    finally { if (state.extractTask === task) { state.extractTask = null; $('btnExtract').disabled = false; actions(); } }
  }

  function applyBook(data) {
    state.book = data.entries; state.serverOffset = data.now - Date.now(); state.bookLoaded = true;
  }
  async function loadBook() {
    if (state.bookLoading) return;
    state.bookLoading = true; $('refreshBook').disabled = true;
    try { applyBook(await json('/api/vocab/book')); render(); }
    catch (error) { status('生词本加载失败：'+error.message, 'error'); }
    finally { state.bookLoading = false; $('refreshBook').disabled = false; }
  }
  async function saveSelected() {
    if (stale() || state.saving) return;
    const selected = rows().filter(r => state.selected.has(r.key));
    if (!selected.length || selected.length > 100) { status('每次请选择 1–100 个单词。', 'error'); return; }
    const words = selected.map(snapshotWord); state.saving = true; actions();
    try {
      applyBook(await post('/api/vocab/book', {words}));
      status('已加入生词本；相同单词与原句会去重，已有复习进度保留。', 'success'); render();
    } catch (error) { status(error.message, 'error'); }
    finally { state.saving = false; actions(); }
  }
  async function replaceWord(row, word) {
    if (!current(row)) throw new Error('词条已经变化，请重新操作');
    if (row.entry) {
      const updated = await json('/api/vocab/book/'+encodeURIComponent(row.entry.id), {method:'PATCH', body:JSON.stringify(word)});
      state.book = state.book.map(e => e.id === updated.id ? updated : e);
    } else {
      state.words = state.words.map(w => w === row.word ? word : w);
    }
  }
  async function explain(row) {
    if (stale() || state.aiTasks.has(row.key)) return;
    const example = contextOf(row);
    if (!example) {
      contextTarget = row; $('contextTitle').textContent = '补充原句 · '+row.word.w;
      $('contextInput').value = ''; $('contextError').textContent = ''; $('contextDialog').showModal();
      $('contextInput').focus(); return;
    }
    state.aiErrors.delete(row.key);
    const task = {controller:new AbortController()}; state.aiTasks.set(row.key, task); render();
    status(`正在为 ${row.word.w} 生成本句解释，会调用已配置的模型…`);
    try {
      const explanation = await post('/api/vocab/explain', {word:row.word, example, level:$('level').value}, {signal:task.controller.signal});
      if (state.aiTasks.get(row.key) !== task || !current(row) || stale()) return;
      await replaceWord(row, {...row.word, explanation});
      status('AI 解释已生成，请核对词义；需要时可手动修改。文章词条需加入生词本才能长期保存。', 'success');
    } catch (error) {
      if (state.aiTasks.get(row.key) === task && current(row) && error.name !== 'AbortError') {
        state.aiErrors.set(row.key, {context:example, message:error.message}); status(error.message, 'error');
      }
    }
    finally { if (state.aiTasks.get(row.key) === task) { state.aiTasks.delete(row.key); render(); } }
  }
  async function submitContext(event) {
    event.preventDefault();
    const row = contextTarget, example = $('contextInput').value.trim();
    if (!row || !current(row) || stale()) { $('contextError').textContent = '文章或词条已经变化，请关闭后重新操作。'; return; }
    const tokens = example.normalize('NFKC').replace(/[’‘ʼ]/g, "'").replace(/[‐‑]/g, '-').match(/[A-Za-z]+(?:['-][A-Za-z]+)*/g) || [];
    if (!example || example.length > 400 || !tokens.some(w => w.toLowerCase() === row.word.w.toLowerCase())) {
      $('contextError').textContent = '请填写包含这个单词的英文原句，最多 400 字符。'; return;
    }
    $('useContext').disabled = true;
    try {
      const word = {...row.word, examples:[example, ...(row.word.examples || []).filter(e => e !== example)].slice(0,3)};
      await replaceWord(row, word); state.contexts.set(row.key, example);
      $('contextDialog').close(); render(); const updated = rows().find(r => r.key === row.key); if (updated && !stale()) await explain(updated);
    } catch (error) { $('contextError').textContent = error.message; }
    finally { $('useContext').disabled = false; }
  }
  function openEditor(row) {
    editTarget = row;
    const explanation = explanationOf(row);
    $('editTitle').textContent = '填写释义 · '+row.word.w;
    $('editCn').value = explanation?.cn || row.word.cn || '';
    $('editEn').value = explanation?.en || '';
    $('editError').textContent = ''; $('editDialog').showModal();
  }
  async function saveEdit(event) {
    event.preventDefault();
    const cn = $('editCn').value.trim(), en = $('editEn').value.trim(), row = editTarget;
    if (!cn && !en) { $('editError').textContent = '请至少填写一种语言的释义。'; return; }
    if (!current(row) || stale()) { $('editError').textContent = '文章或词条已经变化，请关闭后重新操作。'; return; }
    const task = state.aiTasks.get(row.key); task?.controller.abort(); state.aiTasks.delete(row.key);
    $('saveEdit').disabled = true;
    try {
      await replaceWord(row, {...row.word, explanation:{cn,en,source:'手动修改',level:'manual',context:contextOf(row)}});
      $('editDialog').close(); status('修改已保存。文章词条需加入生词本才能长期保存。', 'success'); render();
    } catch (error) { $('editError').textContent = error.message; }
    finally { $('saveEdit').disabled = false; }
  }
  async function review(row, rating) {
    const buttons = document.querySelectorAll(`[data-key="${row.key}"] .review-actions button`);
    buttons.forEach(b => b.disabled = true);
    try {
      const updated = await post('/api/vocab/book/'+row.entry.id+'/review', {rating});
      state.book = state.book.map(e => e.id === updated.id ? updated : e);
      state.revealed.delete(row.key); state.selected.delete(row.key);
      status('复习已记录，下次复习：'+new Date(updated.dueAt).toLocaleString('zh-CN'), 'success'); render();
    } catch (error) { status(error.message, 'error'); buttons.forEach(b => b.disabled = false); }
  }
  async function deleteWord() {
    if (!deleteTarget) return;
    $('confirmDelete').disabled = true;
    try {
      await json('/api/vocab/book/'+deleteTarget.entry.id, {method:'DELETE'});
      state.book = state.book.filter(e => e.id !== deleteTarget.entry.id);
      state.selected.delete(deleteTarget.key); $('deleteDialog').close(); render(); status('该条生词已移出。');
    } catch (error) { status(error.message, 'error'); }
    finally { $('confirmDelete').disabled = false; }
  }

  async function download() {
    if (stale() || state.downloading) return;
    const selected = exportRows(); if (!selected.length) { status('当前范围没有可导出的词条。', 'error'); return; }
    const words = selected.map(snapshotWord), mode = $('mode').value, version = state.version;
    state.downloading = true; actions(); status(`正在导出 ${words.length} 个词条及其原句…`);
    try {
      const response = await authFetch('/api/vocab/docx', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({words,mode})});
      if (!response.ok) { const error = await response.json(); throw new Error(error.error || '导出失败'); }
      const url = URL.createObjectURL(await response.blob()), link = document.createElement('a');
      link.href = url; link.download = `单词手记_${mode === 'en' ? '英英' : '中文'}_${words.length}词.docx`;
      document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      if (version === state.version) status(`已导出 ${words.length} 个词条，内容以点击下载时的结果快照为准。`, 'success');
    } catch (error) { if (version === state.version) status(error.message, 'error'); }
    finally { state.downloading = false; actions(); }
  }

  function actions() {
    const list = filtered(), selected = rows().filter(r => state.selected.has(r.key)).length, count = exportRows().length;
    $('selectionCount').textContent = `已勾选 ${selected} 个`;
    $('selectAll').checked = !!list.length && list.every(r => state.selected.has(r.key));
    $('selectAll').indeterminate = list.some(r => state.selected.has(r.key)) && !$('selectAll').checked;
    $('selectAll').disabled = stale() || !list.length;
    $('saveWords').disabled = stale() || !selected || selected > 100 || state.saving || state.view !== 'article';
    $('saveWords').hidden = state.view !== 'article';
    $('saveWords').textContent = state.saving ? '正在保存…' : selected > 100 ? '每次最多保存 100 个' : '加入生词本';
    $('btnDownload').disabled = stale() || !count || state.downloading;
    $('exportCount').textContent = count;
    $('staleNotice').hidden = !(state.words.length && state.snapshot !== $('article').value.trim());
    $('bookCount').textContent = state.bookLoaded ? state.book.length : '—';
    $('dueCount').textContent = state.bookLoaded ? state.book.filter(e => e.dueAt <= now()).length : '—';
  }
  function card(row) {
    const w = row.word, context = contextOf(row), explanation = explanationOf(row);
    const pending = state.aiTasks.has(row.key), failure = state.aiErrors.get(row.key);
    const aiHint = stale() ? '文章已修改，请重新提取后生成解释。' : pending ? '正在生成，请稍候。' : !context ? '缺少来源原句：点击下方按钮，补充原句后即可生成解释。' : '';
    const aiError = failure?.context === context ? failure.message : '';
    const hidden = state.view === 'review' && !state.revealed.has(row.key), disabled = stale();
    const english = explanation?.en, chinese = explanation?.cn || w.cn;
    const source = explanation?.source || '词典原文';
    return `<article class="word-card" data-key="${esc(row.key)}">
      <div class="word-head"><label class="check"><input type="checkbox" data-action="select" aria-label="勾选 ${esc(w.w)}" ${state.selected.has(row.key)?'checked':''} ${disabled?'disabled':''}><h3>${esc(w.w)}</h3></label>${!hidden&&w.ph?`<span class="phonetic">/${esc(w.ph)}/</span>`:''}</div>
      <div class="meta">出现 ${w.count || 1} 次${w.lemma?` · 原形关系 ${esc(w.lemma)}`:''}${w.tag?` · <span class="tag">${esc(w.tag)}</span>`:''}${row.entry?` · 已复习 ${row.entry.reviewCount} 次`:''}</div>
      <blockquote class="example">${esc(context || '暂无来源原句')}</blockquote>
      ${(w.examples?.length||0)>1?`<label class="small">解释所用原句<select class="context-select" data-action="context" aria-label="选择来源原句">${w.examples.map(e=>`<option value="${esc(e)}" ${e===context?'selected':''}>${esc(e)}</option>`).join('')}</select></label>`:''}
      ${hidden?'<div class="review-hidden">先回想这个词在原句中的意思，再查看答案。</div><button data-action="reveal" type="button">查看释义</button>':`
      ${$('mode').value==='en'?`<p class="meaning-label">${english?'PLAIN ENGLISH · 本句解释':w.en?'DICTIONARY ENGLISH · 词典释义':'ENGLISH · 待补充'}</p>${english?`<span class="provenance">${esc(source)}${explanation.level==='simple'?' · 简单':''}</span><p class="definition english">${esc(english)}</p>`:w.en?`<span class="provenance">词典原文 · 尚未简化</span><p class="definition english">${esc(w.en)}</p><p class="source-note">可能包含多个词义；点击 AI 解释，生成当前原句的易懂释义。</p>`:'<p class="missing">词典暂无英文释义。点击下方按钮，用 AI 补充本句解释，也可以手动填写。</p>'}`:''}
      <p class="meaning-label">中文辅助</p><span class="provenance">${esc(explanation?.cn?source:'词典原文')}</span><p class="definition">${esc(chinese || '暂无中文释义，可使用 AI / 手动补充。')}</p>
      ${!w.cn||!w.en?`<p class="source-note">词典${!w.cn?'缺中文':''}${!w.cn&&!w.en?'、':''}${!w.en?'缺英文':''}${w.lookupStatus==='NOT_FOUND'?' · 未查到完整词条':''}</p>`:''}
      <details class="dictionary"><summary>查看词典原文${w.lookupWord!==w.w?' · '+esc(w.lookupWord):''}</summary><p>中文：${esc(w.cn||'暂无中文释义')}</p><p>英文：${esc(w.en||'暂无英文释义')}</p><p>来源：ECDICT。词典条目可能包含多个词义，未自动判断本句用法。</p></details>
      ${aiHint?`<p class="source-note">${esc(aiHint)}</p>`:''}${aiError?`<p class="ai-error" role="alert">${esc(aiError)}</p>`:''}
      <div class="card-actions"><button data-action="ai" type="button" title="${esc(aiHint || '按所选原句生成中英文解释')}" ${disabled||pending?'disabled':''}>${pending?'正在解释…':!context?'补充原句并 AI 解释':'AI 解释'}</button><button data-action="edit" type="button" ${disabled?'disabled':''}>手动填写 / 修改</button>${row.entry?'<button data-action="delete" type="button" class="danger">移出</button>':''}</div>
      ${row.entry?`<p class="source-note">下次复习：${new Date(row.entry.dueAt).toLocaleString('zh-CN')}</p>`:''}
      ${state.view==='review'?'<div class="review-actions"><button data-rating="again" type="button">不会 · 10 分钟</button><button data-rating="hard" type="button">模糊 · 明天</button><button data-rating="good" type="button">掌握 · 延后复习</button></div>':''}`}
    </article>`;
  }
  function render() {
    $('articlePanel').hidden = state.view !== 'article';
    $('resultsTitle').textContent = {article:'挑出值得记住的词',book:'每一个词，都有来处',review:'把昨天遇见的，再想一遍'}[state.view];
    const list = filtered(); $('wordList').innerHTML = list.slice(0,state.limit).map(card).join('');
    $('showMore').hidden = list.length <= state.limit;
    $('stats').textContent = `${list.length} / ${rows().length} 个词条`;
    $('emptyState').hidden = !!list.length;
    if (!list.length) {
      const heading = state.view === 'article' ? (state.words.length?'没有匹配的词':'从一段阅读开始') : state.view === 'review' ? '当前没有待复习条目' : '生词本还是空的';
      $('emptyState').querySelector('h3').textContent = heading;
      $('emptyState').querySelector('p').textContent = state.view === 'article' ? '粘贴文章并提取，勾选想学的词，连同原句一起保存。' : !state.bookLoaded ? '请先刷新生词本；加载失败时不会覆盖已有数据。' : '可以换个筛选条件，或从文章选词中加入生词。';
    }
    document.querySelectorAll('[data-view]').forEach(button => {
      const active = button.dataset.view === state.view; button.classList.toggle('active',active);
      if (active) button.setAttribute('aria-current','page'); else button.removeAttribute('aria-current');
    }); actions();
  }
  document.addEventListener('DOMContentLoaded', () => {
    try { $('article').value = localStorage.getItem(ARTICLE_KEY) || ''; } catch (_) { }
    $('charCount').textContent = `${$('article').value.length} / 100000`;
    $('article').addEventListener('input',articleChanged); window.addEventListener('pagehide',saveArticle);
    $('btnExtract').addEventListener('click',extract); $('btnClear').addEventListener('click',()=>$('clearDialog').showModal());
    $('confirmClear').addEventListener('click',clearArticle); $('saveWords').addEventListener('click',saveSelected);
    $('btnDownload').addEventListener('click',download); $('refreshBook').addEventListener('click',loadBook);
    $('cancelEdit').addEventListener('click',()=>$('editDialog').close()); $('editForm').addEventListener('submit',saveEdit);
    $('confirmDelete').addEventListener('click',deleteWord);
    $('contextForm').addEventListener('submit',submitContext);
    $('cancelContext').addEventListener('click',()=>$('contextDialog').close());
    for (const id of ['query','filter','mode']) $(id).addEventListener(id==='query'?'input':'change',()=>{state.limit=40;render();});
    $('exportScope').addEventListener('change',actions);
    $('selectAll').addEventListener('change',()=>{filtered().forEach(row=>$('selectAll').checked?state.selected.add(row.key):state.selected.delete(row.key));render();});
    $('showMore').addEventListener('click',()=>{state.limit+=40;render();});
    document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',()=>{
      state.view=button.dataset.view;state.selected.clear();state.limit=40;$('query').value='';$('filter').value='all';
      status(state.view==='review'?'先回想，再展开释义；按真实掌握情况反馈。':'');render();if(state.view!=='article')loadBook();
    }));
    $('wordList').addEventListener('change',event=>{
      const node=event.target.closest('[data-key]');if(!node)return;
      const row=rows().find(r=>r.key===node.dataset.key);if(!row)return;
      if(event.target.dataset.action==='select'){event.target.checked?state.selected.add(row.key):state.selected.delete(row.key);if($('filter').value==='selected')render();else actions();}
      if(event.target.dataset.action==='context'){state.contexts.set(row.key,event.target.value);render();}
    });
    $('wordList').addEventListener('click',event=>{
      const button=event.target.closest('button'),node=event.target.closest('[data-key]');if(!button||!node||button.disabled)return;
      const row=rows().find(r=>r.key===node.dataset.key);if(!row)return;
      if(button.dataset.rating){review(row,button.dataset.rating);return;}
      if(button.dataset.action==='ai')explain(row);
      if(button.dataset.action==='edit')openEditor(row);
      if(button.dataset.action==='reveal'){state.revealed.add(row.key);render();}
      if(button.dataset.action==='delete'){deleteTarget=row;$('deleteDialog').showModal();}
    });
    render();loadBook();
  });
})();
