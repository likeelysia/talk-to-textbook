/* 单词手记：以提取快照为准；文章改动/清空使在途响应失效。 */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  let articleKey = null;
  let identityStopped = false;
  const state = { view: 'article', words: [], book: [], selected: new Set(), contexts: new Map(),
    revealed: new Set(), version: 0, snapshot: null, extractTask: null, importTask: null, aiTasks: new Map(), aiErrors: new Map(),
    limit: 40, saving: false, queueing: false, downloading: false, bookLoading: false, bookLoaded: false, serverOffset: 0, importMode: false,
    planDays: 0, reviewDay: null, dayPage: 1, queuePage: 1, queueDay: 0, queueIds: [] };
  const searches = {article:{query:'',filter:'all'}, book:{query:'',filter:'all'}, review:{query:'',filter:'all'}};
  let saveTimer, editTarget, deleteTarget, contextTarget;
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const stale = () => state.view === 'article' && !state.importMode && state.snapshot !== $('article').value.trim();
  const now = () => Date.now() + state.serverOffset;
  const status = (message, kind = '') => { if (identityStopped) return; $('status').textContent = message; $('status').className = kind; };
  let toastTimer = 0;
  function toast(message) {
    const el = $('toast');
    if (!el) return;
    el.textContent = message; el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 2200);
  }

  async function json(url, options = {}) {
    const response = await authFetch(url, { ...options, headers: {'Content-Type':'application/json', ...(options.headers || {})} });
    if (response.status === 404 && url === '/api/vocab/explain')
      throw new Error('当前运行的后端不支持 AI 解释，请在 IDEA 停止并重新启动项目，再刷新页面重新提取。');
    let data;
    try { data = await response.json(); } catch (_) { throw new Error('服务器没有返回有效结果，请稍后重试'); }
    await verifyVocabIdentity();
    if (!response.ok) throw new Error(data.error || '请求失败，请稍后重试');
    return data;
  }
  const post = (url, body, extra = {}) => json(url, {method:'POST', body:JSON.stringify(body), ...extra});
  function rows() {
    if (state.view === 'article') return state.words.map(word => ({key:'word:'+word.w, word}));
    return state.book.filter(entry => state.view !== 'review' ||
        (entry.queuedAt > 0 && (state.reviewDay == null || entry.planDay === state.reviewDay)))
      .map(entry => ({key:'book:'+entry.id, word:entry.word, entry}));
  }
  function filtered() {
    const q = $('query').value.trim(), filter = $('filter').value;
    let list = rows();
    if (q) {
      list = window.VocabSearch
        ? window.VocabSearch.rank(list, q, explanationOf)
        : list.filter(row => {
            const w = row.word, explanation = explanationOf(row);
            return [w.w, w.lemma, w.cn, w.en, explanation?.cn, explanation?.en, ...(w.examples || [])]
              .some(value => String(value || '').toLowerCase().includes(q.toLowerCase()));
          });
    }
    const result = list.filter(r => filter === 'all' ? true : filter === 'selected' ? state.selected.has(r.key)
      : filter === 'missing' ? !(explanationOf(r)?.cn || r.word.cn) || !(explanationOf(r)?.en || r.word.en)
      : (r.word.tag || '').includes(filter));
    // 星标是“重点”：最终结果里优先，组内保留搜索/筛选/原始顺序。
    return result.sort((a, b) => Number(!!b.entry?.starred) - Number(!!a.entry?.starred));
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
    if (!articleKey || identityStopped) return;
    try {
      if ($('article').value) localStorage.setItem(articleKey, $('article').value);
      else localStorage.removeItem(articleKey);
    } catch (_) { /* Browser draft is optional; server notebook has explicit error handling. */ }
  }
  function invalidate() {
    state.version++;
    state.importTask?.controller.abort();
    state.importTask = null;
    $('btnImport').disabled = false;
    $('btnImport').textContent = '导入文档';
    state.extractTask?.controller.abort();
    state.extractTask = null;
    for (const task of state.aiTasks.values()) task.controller.abort();
    state.aiTasks.clear(); state.aiErrors.clear();
    $('btnExtract').disabled = false;
  }
  function articleChanged() {
    state.importMode = false;
    invalidate(); clearTimeout(saveTimer); saveTimer = setTimeout(saveArticle, 300);
    $('charCount').textContent = `${$('article').value.length} / 200000`;
    if (state.words.length) status('文章已修改，请重新提取；旧词表暂时不能保存或导出。');
    render();
  }
  function clearArticle() {
    state.importMode = false;
    invalidate(); clearTimeout(saveTimer);
    $('article').value = ''; saveArticle();
    state.words = []; state.snapshot = null; state.selected.clear(); state.contexts.clear();
    $('query').value = ''; $('charCount').textContent = '0 / 200000';
    $('clearDialog').close(); status('文章和提取结果已清空，生词本已保留。'); render(); $('article').focus();
  }

  async function importDocument() {
    const file = $('documentFile').files[0];
    $('documentFile').value = ''; // 同一文件修正后可再次选择。
    if (!file || identityStopped) return;
    if (file.size > 8 * 1024 * 1024) { status('文档不能超过 8 MB，请拆分后导入。', 'error'); return; }
    if ($('article').value.trim() && !window.confirm('导入会替换当前文章和提取结果，已保存的生词本会保留。继续吗？')) return;
    invalidate();
    const task = {version:state.version, controller:new AbortController()}; state.importTask = task;
    $('btnImport').disabled = true; $('btnImport').textContent = '正在导入…';
    status('正在读取文档文字，请稍候；编辑文章可取消本次导入。');
    try {
      if (/\.(xlsx|xls)$/i.test(file.name)) {
        await importExcel(file, task);
        return;
      }
      const form = new FormData(); form.append('file', file);
      // 不手动填写 Content-Type：浏览器会为 multipart 加上正确的 boundary。
      const response = await authFetch('/api/vocab/import', {method:'POST', body:form, signal:task.controller.signal});
      const data = await response.json();
      await verifyVocabIdentity();
      if (!response.ok) throw new Error(data.error || '文档导入失败，请稍后重试');
      if (state.importTask !== task || task.version !== state.version || identityStopped) return;
      state.importTask = null;
      state.importMode = false;
      $('article').value = data.text;
      state.words = []; state.snapshot = null; state.selected.clear(); state.contexts.clear();
      articleChanged(); saveArticle();
      status(`已导入「${data.filename}」的 ${data.text.length} 个字符。检查上方文字后，点击「提取并查词」。`, 'success');
      $('article').focus();
    } catch (error) {
      if (state.importTask === task && error.name !== 'AbortError') status('导入失败：'+error.message+'；原文章已保留。', 'error');
    } finally {
      if (state.importTask === task) { state.importTask = null; $('btnImport').disabled = false; $('btnImport').textContent = '导入文档'; }
    }
  }

  function mergeExcelWord(map, word) {
    const key = String(word.w || '').toLowerCase();
    const old = map.get(key);
    if (!old) { map.set(key, {...word, examples:[...(word.examples || [])], forms:[...(word.forms || [])]}); return; }
    old.count = (old.count || 1) + (word.count || 1);
    old.examples = [...new Set([...(old.examples || []), ...(word.examples || [])])];
    old.forms = [...new Set([...(old.forms || []), ...(word.forms || [])])];
    old.cn = old.cn || word.cn; old.en = old.en || word.en;
    old.tag = old.tag || word.tag; old.lemma = old.lemma || word.lemma;
  }

  async function importExcel(file, task) {
    const BATCH = 1000;
    let offset = 0, total = 0, round = 0;
    const merged = new Map();
    while (true) {
      const form = new FormData();
      form.append('file', file); form.append('offset', String(offset)); form.append('limit', String(BATCH));
      const response = await authFetch('/api/vocab/import/excel-batch', {method:'POST', body:form, signal:task.controller.signal});
      const data = await response.json();
      await verifyVocabIdentity();
      if (!response.ok) throw new Error(data.error || 'Excel 导入失败');
      if (state.importTask !== task || task.version !== state.version || identityStopped) return;
      total = data.totalRows;
      if (data.text && data.text.trim()) {
        const extracted = await post('/api/vocab/extract', {article:data.text}, {signal:task.controller.signal});
        if (state.importTask !== task || task.version !== state.version || identityStopped) return;
        for (const word of extracted.words) mergeExcelWord(merged, word);
      }
      offset = data.nextOffset;
      status(`正在分批导入 Excel：${offset}/${total} 行，已合并 ${merged.size} 个词条…`);
      if (data.done) break;
      if (++round > 1000) throw new Error('Excel 行数异常，已停止导入');
    }
    state.words = [...merged.values()]; state.selected.clear(); state.contexts.clear();
    const summary = `已分批导入 Excel「${file.name}」：${total} 行，${state.words.length} 个词条。\n\n下方已生成词表，直接勾选保存；要按普通文章重新提取，请点“清空文章”。`;
    $('article').value = summary; state.snapshot = summary; state.importMode = true;
    $('charCount').textContent = `${summary.length} / 200000`; saveArticle();
    status(`${summary.split('\n')[0]} 已生成词表，可直接勾选保存。`, 'success'); render();
  }

  async function extract() {
    if (state.importMode) { status('Excel 已分批导入，可直接勾选词条保存；要重新提取请先清空或修改上方文字。', 'error'); return; }
    const article = $('article').value.trim();
    if (!article) { status('请先粘贴英文文章。', 'error'); return; }
    if (article.length > 200000) { status('文章超过 200000 字符，请分段处理。', 'error'); return; }
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
  async function loadPlan() {
    try {
      const plan = await json('/api/vocab/review-plan');
      state.planDays = plan.days || 0;
      if (state.reviewDay && state.reviewDay > state.planDays) state.reviewDay = null;
      render();
    } catch (error) { status('复习计划加载失败：'+error.message, 'error'); }
  }
  function dayPages(days) { return Math.max(1, Math.ceil(Math.max(1, days) / 10)); }
  function dayPageHtml(page, selected) {
    const start = (page - 1) * 10 + 1;
    const end = Math.min(state.planDays, start + 9);
    let html = '';
    for (let d = start; d <= end; d++) html += `<button type="button" data-day="${d}" class="${selected === d ? 'active' : ''}">第 ${d} 天</button>`;
    return html;
  }
  function renderDayBar() {
    const bar = $('dayBar');
    bar.hidden = state.view !== 'review';
    if (bar.hidden) return;
    if (!state.planDays) {
      $('dayButtons').innerHTML = '<button type="button" data-day="all" class="active">全部浏览</button>';
      $('dayPageLabel').textContent = '还没有天数计划';
      $('dayPrev').disabled = $('dayNext').disabled = true;
      $('newPlan').hidden = false;
      return;
    }
    const pages = dayPages(state.planDays);
    state.dayPage = Math.min(Math.max(1, state.dayPage), pages);
    const start = (state.dayPage - 1) * 10 + 1, end = Math.min(state.planDays, start + 9);
    $('dayButtons').innerHTML = `<button type="button" data-day="all" class="${state.reviewDay == null ? 'active' : ''}">全部浏览</button>` +
      dayPageHtml(state.dayPage, state.reviewDay);
    $('dayPageLabel').textContent = `第 ${start}–${end} 天 / 共 ${state.planDays} 天`;
    $('dayPrev').disabled = state.dayPage <= 1;
    $('dayNext').disabled = state.dayPage >= pages;
    $('newPlan').hidden = false;
  }
  function openPlanDialog() {
    $('planDays').value = state.planDays || 30; $('planError').textContent = '';
    $('planDialog').showModal();
  }
  async function submitPlan(event) {
    event.preventDefault();
    const days = Number($('planDays').value);
    if (!Number.isInteger(days) || days < 1 || days > 180) { $('planError').textContent = '请输入 1–180 的整数'; return; }
    try {
      const plan = await post('/api/vocab/review-plan', {days});
      state.planDays = plan.days; state.reviewDay = null; state.dayPage = 1;
      $('planDialog').close(); render(); status(`已创建 ${days} 天复习计划。`, 'success');
      if ($('queueDialog').open) renderQueueDialog();
    } catch (error) { $('planError').textContent = error.message; }
  }
  function renderQueueDialog() {
    const planText = state.planDays ? `计划共 ${state.planDays} 天，每天最多 200 词。` : '还没有天数计划，可先直接加入今日复习，或新建计划。';
    $('queueHint').textContent = `已选 ${state.queueIds.length} 个词。${planText}`;
    if (state.planDays) {
      const pages = dayPages(state.planDays);
      state.queuePage = Math.min(Math.max(1, state.queuePage), pages);
      const start = (state.queuePage - 1) * 10 + 1, end = Math.min(state.planDays, start + 9);
      $('queueDays').innerHTML = dayPageHtml(state.queuePage, state.queueDay);
      $('queuePageLabel').textContent = `第 ${start}–${end} 天 / 共 ${state.planDays} 天`;
      $('queuePrev').disabled = state.queuePage <= 1;
      $('queueNext').disabled = state.queuePage >= pages;
    } else {
      $('queueDays').innerHTML = '';
      $('queuePageLabel').textContent = '未创建计划';
      $('queuePrev').disabled = $('queueNext').disabled = true;
    }
    $('queueConfirm').textContent = state.queueDay > 0 ? `加入第 ${state.queueDay} 天` : '加入第几天（请选择）';
    $('queueConfirm').disabled = state.queueDay <= 0;
    $('queueDirect').classList.toggle('primary', state.queueDay === 0);
  }
  function openQueueDialog() {
    const selected = rows().filter(r => state.selected.has(r.key) && r.entry);
    if (!selected.length) { status('请先在生词本勾选要复习的词。', 'error'); return; }
    state.queueIds = selected.map(r => r.entry.id);
    state.queueDay = 0; state.queuePage = 1;
    renderQueueDialog(); $('queueDialog').showModal();
  }
  async function queueToDay(day) {
    if (identityStopped || state.queueing || !state.queueIds.length) return;
    if (day > 0 && !state.planDays) { status('请先新建天数计划。', 'error'); return; }
    state.queueing = true;
    const fromReview = state.view === 'review';
    try {
      const data = await post('/api/vocab/book/review-queue', {ids:state.queueIds, day});
      applyBook(data); state.selected.clear(); state.queueIds = [];
      if (fromReview) state.view = 'review';
      state.limit = 40;
      $('query').value = ''; $('filter').value = 'all'; $('queueDialog').close();
      const message = day > 0 ? `已加入第 ${day} 天，同时保留在“全部浏览”。` : '已直接加入今日复习，也会显示在“全部浏览”。';
      status(message, 'success'); toast(message); render();
    } catch (error) { status('加入今日复习失败：'+error.message, 'error'); }
    finally { state.queueing = false; }
  }
  async function saveSelected() {
    if (identityStopped || stale() || state.saving || state.view !== 'article') return;
    await saveRows(rows().filter(r => state.selected.has(r.key)));
  }
  async function saveAllWords() {
    if (identityStopped || stale() || state.saving || state.view !== 'article') return;
    const all = rows(); // 全部提取结果，不受搜索、筛选或“再显示”分页影响。
    state.selected = new Set(all.map(row => row.key));
    render();
    await saveRows(all);
  }
  async function saveRows(selected) {
    if (!selected.length) { status('请先提取并选择单词。', 'error'); return; }
    if (selected.length > 5000) { status('一次最多加入 5000 条；本次没有保存，请减少勾选数量。', 'error'); return; }
    const words = selected.map(snapshotWord), version = state.version;
    state.saving = true; actions(); status(`正在加入 ${words.length} 个词条…`);
    try {
      const data = await post('/api/vocab/book/bulk', {words});
      if (identityStopped) return;
      applyBook(data);
      if (version === state.version) {
        const message = `已加入 ${words.length} 个词条；生词本现有 ${data.entries.length} 条。`;
        status(message + '相同单词和原句已去重，已有复习进度保留。', 'success');
        toast(message);
        state.selected.clear();
      }
      render();
    } catch (error) { if (!identityStopped && version === state.version) status('保存未确认：'+error.message+'。可刷新生词本核对后重试，相同单词和原句会自动去重。', 'error'); }
    finally { state.saving = false; actions(); }
  }
  async function queueSelected() {
    if (identityStopped || state.queueing || (state.view !== 'book' && state.view !== 'review')) return;
    openQueueDialog();
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
    $('editPos').value = row.word.customPos || '';
    $('editCn').value = explanation?.cn || row.word.cn || '';
    $('editEn').value = explanation?.en || '';
    $('editError').textContent = ''; $('editDialog').showModal();
  }
  async function saveEdit(event) {
    event.preventDefault();
    const pos = $('editPos').value.trim(), cn = $('editCn').value.trim(), en = $('editEn').value.trim(), row = editTarget;
    if (!cn && !en) { $('editError').textContent = '请至少填写一种语言的释义。'; return; }
    if (!current(row) || stale()) { $('editError').textContent = '文章或词条已经变化，请关闭后重新操作。'; return; }
    const task = state.aiTasks.get(row.key); task?.controller.abort(); state.aiTasks.delete(row.key);
    $('saveEdit').disabled = true;
    try {
      await replaceWord(row, {...row.word, customPos: pos || null,
        explanation:{cn,en,source:'自定义',level:'manual',context:contextOf(row)}});
      $('editDialog').close(); status('自定义词性和释义已保存。', 'success'); toast('自定义内容已保存'); render();
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
  async function toggleStar(row) {
    if (!row.entry || state.queueing) return;
    try {
      const updated = await post('/api/vocab/book/'+row.entry.id+'/star', {starred:!row.entry.starred});
      state.book = state.book.map(e => e.id === updated.id ? updated : e);
      status(updated.starred ? '已标记这个单词。' : '已取消标记。', 'success'); render();
    } catch (error) { status('标记失败：'+error.message, 'error'); }
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
      const blob = await response.blob();
      await verifyVocabIdentity();
      const url = URL.createObjectURL(blob), link = document.createElement('a');
      link.href = url; link.download = `单词手记_${mode === 'en' ? '英英' : '中文'}_${words.length}词.docx`;
      document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      if (version === state.version) status(`已导出 ${words.length} 个词条，内容以点击下载时的结果快照为准。`, 'success');
    } catch (error) { if (version === state.version) status(error.message, 'error'); }
    finally { state.downloading = false; actions(); }
  }

  function actions() {
    if (identityStopped) return;
    const imported = state.view === 'article' && state.importMode;
    $('article').readOnly = imported;
    $('btnExtract').disabled = imported;
    $('btnExtract').textContent = imported ? '已分批导入' : '提取并查词';
    const list = filtered(), selected = rows().filter(r => state.selected.has(r.key)).length, count = exportRows().length;
    $('selectionCount').textContent = `已勾选 ${selected} 个`;
    $('selectAll').checked = !!list.length && list.every(r => state.selected.has(r.key));
    $('selectAll').indeterminate = list.some(r => state.selected.has(r.key)) && !$('selectAll').checked;
    $('selectAll').disabled = stale() || !list.length;
    $('saveWords').disabled = stale() || !selected || selected > 5000 || state.saving || state.view !== 'article';
    $('saveWords').hidden = state.view !== 'article';
    $('saveWords').textContent = state.saving ? '正在保存…' : selected > 5000 ? '每次最多保存 5000 个' : '加入生词本';
    $('saveAllWords').hidden = state.view !== 'article';
    $('bulkHelp').hidden = state.view !== 'article';
    $('saveAllWords').disabled = stale() || !state.words.length || state.saving || state.words.length > 5000;
    $('saveAllWords').textContent = state.saving ? '正在保存…' : '全选并加入生词本';
    $('queueReview').hidden = state.view !== 'book' && state.view !== 'review';
    $('queueReview').disabled = !selected || state.queueing;
    $('queueReview').textContent = state.queueing ? '正在加入…'
      : state.view === 'review' ? `加入天数计划${selected ? ' (' + selected + ')' : ''}`
      : `加入今日复习${selected ? ' (' + selected + ')' : ''}`;
    $('btnDownload').disabled = stale() || !count || state.downloading;
    $('exportCount').textContent = count;
    $('staleNotice').hidden = !(state.words.length && state.snapshot !== $('article').value.trim());
    $('bookCount').textContent = state.bookLoaded ? state.book.length : '—';
    $('dueCount').textContent = state.bookLoaded ? state.book.filter(e => e.queuedAt > 0).length : '—';
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
      <div class="word-head"><label class="check"><input type="checkbox" data-action="select" aria-label="勾选 ${esc(w.w)}" ${state.selected.has(row.key)?'checked':''} ${disabled?'disabled':''}><h3>${esc(w.w)}</h3>${row.entry?.starred?'<span class="star-mark" aria-label="已标记">★</span>':''}</label>${!hidden&&w.ph?`<span class="phonetic">/${esc(w.ph)}/</span>`:''}</div>
      <div class="meta">出现 ${w.count || 1} 次${w.lemma?` · 原形关系 ${esc(w.lemma)}`:''}${w.tag?` · <span class="tag">${esc(w.tag)}</span>`:''}${w.customPos?` · <span class="custom-badge">自定义词性：${esc(w.customPos)}</span>`:''}${row.entry?` · 已复习 ${row.entry.reviewCount} 次`:''}</div>
      <blockquote class="example">${esc(context || '暂无来源原句')}</blockquote>
      ${(w.examples?.length||0)>1?`<label class="small">解释所用原句<select class="context-select" data-action="context" aria-label="选择来源原句">${w.examples.map(e=>`<option value="${esc(e)}" ${e===context?'selected':''}>${esc(e)}</option>`).join('')}</select></label>`:''}
      ${hidden?'<div class="review-hidden">先回想这个词在原句中的意思，再查看答案。</div><button data-action="reveal" type="button">查看释义</button>':`
      ${$('mode').value==='en'?`<p class="meaning-label">${english?'PLAIN ENGLISH · 本句解释':w.en?'DICTIONARY ENGLISH · 词典释义':'ENGLISH · 待补充'}</p>${english?`<span class="provenance">${esc(source)}${explanation.level==='simple'?' · 简单':''}</span><p class="definition english">${esc(english)}</p>`:w.en?`<span class="provenance">词典原文 · 尚未简化</span><p class="definition english">${esc(w.en)}</p><p class="source-note">可能包含多个词义；点击 AI 解释，生成当前原句的易懂释义。</p>`:'<p class="missing">词典暂无英文释义。点击下方按钮，用 AI 补充本句解释，也可以手动填写。</p>'}`:''}
      <p class="meaning-label">中文辅助</p><span class="provenance">${esc(explanation?.cn?source:'词典原文')}</span><p class="definition">${esc(chinese || '暂无中文释义，可使用 AI / 手动补充。')}</p>
      ${!w.cn||!w.en?`<p class="source-note">词典${!w.cn?'缺中文':''}${!w.cn&&!w.en?'、':''}${!w.en?'缺英文':''}${w.lookupStatus==='NOT_FOUND'?' · 未查到完整词条':''}</p>`:''}
      <details class="dictionary"><summary>查看词典原文${w.lookupWord!==w.w?' · '+esc(w.lookupWord):''}</summary><p>中文：${esc(w.cn||'暂无中文释义')}</p><p>英文：${esc(w.en||'暂无英文释义')}</p><p>来源：ECDICT。词典条目可能包含多个词义，未自动判断本句用法。</p></details>
      ${aiHint?`<p class="source-note">${esc(aiHint)}</p>`:''}${aiError?`<p class="ai-error" role="alert">${esc(aiError)}</p>`:''}
      <div class="card-actions"><button data-action="ai" type="button" title="${esc(aiHint || '按所选原句生成中英文解释')}" ${disabled||pending?'disabled':''}>${pending?'正在解释…':!context?'补充原句并 AI 解释':'AI 解释'}</button><button data-action="edit" type="button" ${disabled?'disabled':''}>手动填写 / 修改</button>${state.view==='review'&&row.entry?`<button data-action="star" type="button" class="${row.entry.starred?'starred':''}" aria-pressed="${row.entry.starred}">${row.entry.starred?'★ 已标记':'☆ 标记'}</button>`:''}${row.entry?'<button data-action="delete" type="button" class="danger">移出</button>':''}</div>
      ${row.entry?`<p class="source-note">下次复习：${new Date(row.entry.dueAt).toLocaleString('zh-CN')}${row.entry.planDay>0?' · 第 '+row.entry.planDay+' 天':''}</p>`:''}
      ${state.view==='review'?'<div class="review-actions"><button data-rating="again" type="button">不会 · 10 分钟</button><button data-rating="hard" type="button">模糊 · 明天</button><button data-rating="good" type="button">掌握 · 延后复习</button></div>':''}`}
    </article>`;
  }
  function render() {
    if (identityStopped) return;
    $('articlePanel').hidden = state.view !== 'article';
    $('resultsTitle').textContent = {article:'挑出值得记住的词',book:'每一个词，都有来处',review:'把昨天遇见的，再想一遍'}[state.view];
    $('searchLabel').textContent = {article:'查找文章中的单词',book:'查找我的生词本',review:'查找今日待复习词'}[state.view];
    const list = filtered();
    $('searchCount').textContent = `找到 ${list.length} / ${rows().length} 条（包含尚未展开的词条）`;
    $('wordList').innerHTML = list.slice(0,state.limit).map(card).join('');
    $('showMore').hidden = list.length <= state.limit;
    $('stats').textContent = `${list.length} / ${rows().length} 个词条`;
    $('emptyState').hidden = !!list.length;
    if (!list.length) {
      const heading = state.view === 'article' ? (state.words.length?'没有匹配的词':'从一段阅读开始') : state.view === 'review' ? '今日复习是空的' : '生词本还是空的';
      $('emptyState').querySelector('h3').textContent = heading;
      $('emptyState').querySelector('p').textContent = state.view === 'article' ? '粘贴文章并提取，勾选想学的词，连同原句一起保存。'
        : state.view === 'review' ? '去「我的生词本」勾选单词，点“加入今日复习”并确认后，才会出现在这里。'
        : !state.bookLoaded ? '请先刷新生词本；加载失败时不会覆盖已有数据。' : '可以换个筛选条件，或从文章选词中加入生词。';
    }
    document.querySelectorAll('[data-view]').forEach(button => {
      const active = button.dataset.view === state.view; button.classList.toggle('active',active);
      if (active) button.setAttribute('aria-current','page'); else button.removeAttribute('aria-current');
    }); renderDayBar(); actions();
  }
  window.addEventListener('vocab-identity-changed', () => {
    identityStopped = true;
    Object.values(searches).forEach(search=>{search.query='';search.filter='all';});
    document.body.inert = true;
    invalidate();
    clearTimeout(saveTimer);
    state.words = []; state.book = []; state.selected.clear(); state.contexts.clear();
    state.snapshot = null;
    editTarget = deleteTarget = contextTarget = null;
    articleKey = null;
    document.querySelectorAll('dialog[open]').forEach(dialog => dialog.close());
    document.querySelectorAll('input, textarea').forEach(input => {
      input.value = ''; input.checked = false;
    });
    $('wordList').replaceChildren();
    $('bookCount').textContent = '—'; $('dueCount').textContent = '—';
    $('charCount').textContent = '0 / 200000'; $('selectionCount').textContent = '';
    $('status').textContent = '登录身份已变化，请刷新页面后继续。';
    $('status').className = 'error';
    document.querySelectorAll('button, input, textarea, select').forEach(input => { input.disabled = true; });
  });

  document.addEventListener('DOMContentLoaded', async () => {
    try {
      const ownerId = await initVocabIdentity();
      articleKey = 'vocab.article.v2.' + ownerId;
      // 旧版共用草稿归属不明，不导入到当前账号。
    } catch (_) { return; }
    try { $('article').value = localStorage.getItem(articleKey) || ''; } catch (_) { }
    $('charCount').textContent = `${$('article').value.length} / 200000`;
    $('article').addEventListener('input',articleChanged); window.addEventListener('pagehide',saveArticle);
    $('btnImport').addEventListener('click',()=>$('documentFile').click());
    $('documentFile').addEventListener('change',importDocument);
    $('btnExtract').addEventListener('click',extract); $('btnClear').addEventListener('click',()=>$('clearDialog').showModal());
    $('saveAllWords').addEventListener('click',saveAllWords);
    $('queueReview').addEventListener('click',queueSelected);
    $('newPlan').addEventListener('click',openPlanDialog);
    $('planForm').addEventListener('submit',submitPlan);
    $('cancelPlan').addEventListener('click',()=>$('planDialog').close());
    $('dayButtons').addEventListener('click',event=>{
      const button=event.target.closest('[data-day]'); if(!button)return;
      state.reviewDay=button.dataset.day==='all'?null:Number(button.dataset.day); state.limit=40; render();
    });
    $('dayPrev').addEventListener('click',()=>{if(state.dayPage>1){state.dayPage--;render();}});
    $('dayNext').addEventListener('click',()=>{if(state.dayPage<dayPages(state.planDays)){state.dayPage++;render();}});
    $('queueDays').addEventListener('click',event=>{
      const button=event.target.closest('[data-day]'); if(!button)return;
      state.queueDay=Number(button.dataset.day); renderQueueDialog();
    });
    $('queuePrev').addEventListener('click',()=>{if(state.queuePage>1){state.queuePage--;renderQueueDialog();}});
    $('queueNext').addEventListener('click',()=>{if(state.queuePage<dayPages(state.planDays)){state.queuePage++;renderQueueDialog();}});
    $('queueNewPlan').addEventListener('click',openPlanDialog);
    $('queueDirect').addEventListener('click',()=>queueToDay(0));
    $('queueConfirm').addEventListener('click',()=>queueToDay(state.queueDay));
    $('queueCancel').addEventListener('click',()=>$('queueDialog').close());
    $('clearSearch').addEventListener('click',()=>{$('query').value='';state.limit=40;render();$('query').focus();});
    $('confirmClear').addEventListener('click',clearArticle); $('saveWords').addEventListener('click',saveSelected);
    $('btnDownload').addEventListener('click',download); $('refreshBook').addEventListener('click',loadBook);
    $('cancelEdit').addEventListener('click',()=>$('editDialog').close()); $('editForm').addEventListener('submit',saveEdit);
    $('confirmDelete').addEventListener('click',deleteWord);
    $('contextForm').addEventListener('submit',submitContext);
    $('cancelContext').addEventListener('click',()=>$('contextDialog').close());
    let searchTimer = 0;
    $('query').addEventListener('input', () => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => { state.limit = 40; render(); }, 120);
    });
    for (const id of ['filter','mode']) $(id).addEventListener('change',()=>{state.limit=40;render();});
    $('exportScope').addEventListener('change',actions);
    $('selectAll').addEventListener('change',()=>{filtered().forEach(row=>$('selectAll').checked?state.selected.add(row.key):state.selected.delete(row.key));render();});
    $('showMore').addEventListener('click',()=>{state.limit+=40;render();});
    document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',()=>{
      searches[state.view] = {query:$('query').value, filter:$('filter').value};
      state.view=button.dataset.view;state.selected.clear();state.limit=40;
      $('query').value=searches[state.view].query;$('filter').value=searches[state.view].filter;
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
      if(button.dataset.action==='star')toggleStar(row);
      if(button.dataset.action==='delete'){deleteTarget=row;$('deleteDialog').showModal();}
    });
    render();loadBook();loadPlan();
  });
})();
