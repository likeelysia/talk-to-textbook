(function () {
  const $ = id => document.getElementById(id);
  let csrf = '';
  let accounts = [];
  let usage = {};

  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));

  async function getCsrf() {
    const data = await fetch('/api/auth/csrf', { credentials: 'same-origin' }).then(r => r.json());
    csrf = data.token || '';
    if (csrf) return;
    const cookie = document.cookie.split(';').map(s => s.trim()).find(s => s.startsWith('XSRF-TOKEN='));
    if (cookie) csrf = decodeURIComponent(cookie.slice('XSRF-TOKEN='.length));
  }

  async function api(url, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = Object.assign({ Accept: 'application/json' }, options.headers || {});
    if (method !== 'GET' && method !== 'HEAD') {
      if (!csrf) await getCsrf();
      headers['X-XSRF-TOKEN'] = csrf;
      if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(url, Object.assign({}, options, { headers, credentials: 'same-origin' }));
    if (response.status === 401) { location.href = '/login.html'; throw new Error('请先登录'); }
    const text = await response.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { error: text }; }
    if (!response.ok) throw new Error(data.error || ('请求失败 ' + response.status));
    return data;
  }

  function showMessage(text, bad) {
    const el = $('createMsg');
    el.textContent = text || '';
    el.style.color = bad ? 'var(--danger)' : 'var(--brand)';
  }

  function number(value) { return Number(value || 0).toLocaleString('zh-CN'); }
  function money(micros) { return '¥' + (Number(micros || 0) / 1_000_000).toFixed(2); }

  function render() {
    const body = $('accounts');
    body.innerHTML = '';
    if (!accounts.length) {
      body.innerHTML = '<tr><td colspan="7" class="empty">还没有账号</td></tr>';
      return;
    }
    accounts.forEach(account => {
      const u = usage[account.userId] || {};
      const daily = u.dailyRequests || 0;
      const monthly = u.monthlyRequests || 0;
      const today = u.todayRequests || 0;
      const month = u.monthRequests || 0;
      const cost = u.monthCostMicros || 0;
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><strong>${esc(account.username)}</strong><span class="small">${esc(account.userId)}</span></td>
        <td>${account.role === 'ADMIN' ? '管理员' : '普通用户'}</td>
        <td><span class="badge ${account.enabled ? '' : 'off'}">${account.enabled ? '启用' : '停用'}</span></td>
        <td>${number(today)} / ${daily ? number(daily) : '不限'}</td>
        <td>${number(month)} / ${monthly ? number(monthly) : '不限'}<span class="small">本月估算 ${money(cost)}</span></td>
        <td>${daily ? number(daily) : '不限'} / ${monthly ? number(monthly) : '不限'}</td>
        <td><div class="actions">
          <button data-action="quota" data-id="${esc(account.userId)}" data-daily="${daily}" data-monthly="${monthly}">改额度</button>
          <button data-action="reset" data-id="${esc(account.userId)}">重置密码</button>
          <button data-action="${account.enabled ? 'disable' : 'enable'}" data-id="${esc(account.userId)}"
            class="${account.enabled ? 'danger' : ''}">${account.enabled ? '停用' : '启用'}</button>
        </div></td>`;
      body.appendChild(tr);
    });
  }

  async function load() {
    $('refresh').disabled = true;
    try {
      const [accountData, usageData] = await Promise.all([
        api('/api/admin/accounts'), api('/api/admin/accounts/usage')
      ]);
      accounts = accountData.accounts || [];
      usage = usageData.users || {};
      $('global').textContent = `${number(usageData.globalMonthRequests)} 次`;
      const limit = usageData.globalMonthlyRequests || 0;
      if (limit) $('global').textContent += ` / 上限 ${number(limit)} 次`;
      render();
    } catch (error) {
      showMessage(error.message, true);
      if (String(error.message).includes('管理员')) $('denied').hidden = false;
    } finally {
      $('refresh').disabled = false;
    }
  }

  async function createAccount() {
    const username = $('newUsername').value.trim();
    const password = $('newPassword').value;
    const role = $('newRole').value;
    if (!username || !password) { showMessage('请填写用户名和密码。', true); return; }
    $('createAccount').disabled = true;
    try {
      await api('/api/admin/accounts', {
        method: 'POST', body: JSON.stringify({ username, password, role })
      });
      $('newUsername').value = ''; $('newPassword').value = '';
      showMessage(`已创建 ${username}`);
      await load();
    } catch (error) {
      showMessage(error.message, true);
    } finally {
      $('createAccount').disabled = false;
    }
  }

  async function action(event) {
    const button = event.target.closest('button[data-action]');
    if (!button) return;
    const id = button.dataset.id;
    const account = accounts.find(a => a.userId === id);
    if (!account) return;
    try {
      if (button.dataset.action === 'quota') {
        const daily = prompt('每天最多调用多少次？0 = 不限', button.dataset.daily || '0');
        if (daily === null) return;
        const monthly = prompt('每月最多调用多少次？0 = 不限', button.dataset.monthly || '0');
        if (monthly === null) return;
        await api(`/api/admin/accounts/${encodeURIComponent(id)}/quota`, {
          method: 'POST',
          body: JSON.stringify({ dailyRequests: Number(daily), monthlyRequests: Number(monthly) })
        });
        showMessage('额度已更新');
      } else if (button.dataset.action === 'reset') {
        if (!confirm(`重置 ${account.username} 的密码？该账号会被强制下线。`)) return;
        const password = prompt('输入新密码（至少 8 位）');
        if (!password) return;
        await api(`/api/admin/accounts/${encodeURIComponent(id)}/reset-password`, {
          method: 'POST', body: JSON.stringify({ password })
        });
        showMessage('密码已重置，旧会话已失效');
      } else if (button.dataset.action === 'disable') {
        if (!confirm(`停用 ${account.username}？`)) return;
        await api(`/api/admin/accounts/${encodeURIComponent(id)}/disable`, { method: 'POST' });
        showMessage('账号已停用');
      } else if (button.dataset.action === 'enable') {
        await api(`/api/admin/accounts/${encodeURIComponent(id)}/enable`, { method: 'POST' });
        showMessage('账号已启用');
      }
      await load();
    } catch (error) {
      showMessage(error.message, true);
    }
  }

  async function init() {
    try {
      const me = await fetch('/api/auth/me', { credentials: 'same-origin' }).then(r => r.json());
      if (!me.authenticated || me.role !== 'ADMIN') {
        $('denied').hidden = false;
        return;
      }
      $('panel').hidden = false;
      await getCsrf();
      await load();
    } catch (_) {
      $('denied').hidden = false;
    }
  }

  $('refresh').addEventListener('click', load);
  $('createAccount').addEventListener('click', createAccount);
  $('accounts').addEventListener('click', action);
  init();
})();
