/*
 * 背词页专用的认证 / CSRF 请求封装。
 *
 * 这个页面不加载聊天页的 app.js，所以不能依赖那边的 authFetch；
 * 但 POST 仍然必须带 Spring Security 的 CSRF token，登录过期也要给明确提示。
 *
 * 注意: 403 不自动重放 POST。像“提取/生成 Word”这种请求可能已经执行过，
 * 自动重放可能产生重复操作；只提示用户刷新后重新点。
 */
(function (global) {
    'use strict';

    var CSRF_COOKIE = 'XSRF-TOKEN';
    var CSRF_HEADER = 'X-XSRF-TOKEN';

    function readCookie(name) {
        var prefix = name + '=';
        var parts = document.cookie.split(';');
        for (var i = 0; i < parts.length; i++) {
            var item = parts[i].trim();
            if (item.indexOf(prefix) === 0) {
                return decodeURIComponent(item.substring(prefix.length));
            }
        }
        return '';
    }

    function redirectToLogin() {
        if (location.pathname.lastIndexOf('/login.html') >= 0) return;
        location.href = '/login.html?expired=1';
    }

    async function getCsrfToken() {
        var token = readCookie(CSRF_COOKIE);
        if (token) return token;

        // 静态页首次打开时 Cookie 可能还没写到 JS 可见的位置:向服务端要一次。
        var res = await fetch('/api/auth/csrf', {
            method: 'GET',
            credentials: 'same-origin',
            cache: 'no-store'
        });
        if (!res.ok) throw new Error('无法获取安全校验信息');
        var data = await res.json();
        return data.token || readCookie(CSRF_COOKIE);
    }

    async function authFetch(url, options) {
        var opts = Object.assign({credentials: 'same-origin'}, options || {});
        var method = String(opts.method || 'GET').toUpperCase();

        if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
            var token = await getCsrfToken();
            if (!token) throw new Error('安全校验信息缺失，请刷新页面后重试');
            opts.headers = Object.assign({}, opts.headers || {}, {
                'X-XSRF-TOKEN': token
            });
        }

        var res = await fetch(url, opts);
        if (res.status === 401) {
            redirectToLogin();
            throw new Error('登录已过期，请重新登录');
        }
        if (res.status === 403) {
            // 不自动重放可能已经执行过的 POST;让用户刷新后重新操作。
            throw new Error('安全校验失败，请刷新页面后重新操作');
        }
        return res;
    }

    async function authJson(url, body) {
        var res = await authFetch(url, {
            method: 'POST',
            headers: {'Content-Type': 'application/json; charset=UTF-8'},
            body: JSON.stringify(body)
        });
        if (!res.ok) {
            var message = '请求失败';
            try {
                var data = await res.json();
                if (data && data.error) message = data.error;
            } catch (e) { /* 非 JSON 错误响应 */ }
            throw new Error(message);
        }
        return res.json();
    }

    global.authFetch = authFetch;
    global.authJson = authJson;
})(window);
