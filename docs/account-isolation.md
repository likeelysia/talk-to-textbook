# 账号与笔记隔离（首轮设计）

日期：2026-10-26。状态：**本地中间成果，未部署、未对测试者开放。**

## 1. 账号

- 存储：SQLite `data/accounts/accounts.db`（`fa.account.db` 可改）；只存账号/角色/状态/会话版本，不把知识库搬进数据库。
- 字段：`userId`（服务端生成，稳定）、`username`（唯一）、`passwordHash`（BCrypt）、`enabled`、`role`、`createdAt`、`sessionVersion`。
- 初始管理员：账号库为空时，用现有 `FA_AUTH_USERNAME` + `FA_AUTH_PASSWORD_HASH`/`FA_AUTH_PASSWORD` 创建 ADMIN；不新建明文口令文件。
- 邀请账号：管理员通过受保护接口创建，不开放注册；角色 `ADMIN`/`USER`。
- 停用/重置密码：递增 `sessionVersion`；每个已登录请求由过滤器重新核对，旧 HttpSession 下一次请求失效。
- owner 来源：`CurrentUser` 只从 Spring Security 认证主体取 `userId`；请求体里的 `ownerId`/`userId` 不参与归属。

## 2. 笔记

- 文件布局：`data/notes/<userId>/notes.json`；旧 `data/notes/notes.json` 只迁给管理员一次，原文件保留。
- 普通测试账号：`POST /api/notes/manual` 创建（问题+回答，不引用尚未隔离的会话），可列表、按 ID 读取、编辑、删除、导出。
- 会话保存路径：`POST /api/notes` 对管理员按 owner 写入本人目录；普通 USER 只有在会话归属校验通过时才允许，当前没有 USER 会话，因此返回 403。管理员保存前也检查会话归属，不再写全局文件。
- 跨账号访问：按 ID 读取/编辑/删除统一 404；不返回正文或所有者信息。
- 旧笔记迁移：目标不存在整本复制；目标已存在则只补 id 缺失的旧笔记，不覆盖编辑内容；迁移完成写 `.legacy-migrated`，重复启动不重复追加。

## 2.1 三项返工补充

- 管理员聊天保存：`NoteController → NoteService → NoteStore` 统一传认证 owner；去重、列表、导出同目录。
- 会话版本：首次业务请求前也不借用最新数据库版本，必须和登录主体快照一致；停用/重置后的旧 session 首次业务请求即 401。
- 公共教材管理：USER 即使带正确 `X-Admin-Key` 也由角色过滤器拒绝；删除入口再做一次 ADMIN 校验；`X-Admin-Key` 缺失时管理接口 503 拒绝，不静默放行。


## 3. 未隔离入口

普通 USER 在服务端被拒绝：

```text
/api/conversations
/api/agent
/api/memory
/api/vocab
/api/upload
/api/ingest
/api/knowledge/vectors
/api/admin
```

`/study.html`、`/vocab.html` 重定向到 `/notes.html`。管理员仍可使用现有个人功能。这个拒绝是**服务端**，不是隐藏按钮。

## 4. 明确未完成

- 会话/消息/摘要、核心档案/长期记忆/scope、生词/复习、上传/异步任务、私有检索/Agent 工具均未完成 owner 隔离。
- 普通测试账号当前只有“手工笔记”可用；没有聊天、没有教材问答。
- 迁移目前是文件级 + 单元验证；没有生产数据迁移演练。
- 没有部署到服务器；没有多用户并发/2GB 容量结论。
- SQLite 备份需停写后复制主库；未实现在线备份工具。

## 5. 本地验收

用 A/B 两个独立 HTTP 会话验证：

- A/B 登录后各自笔记初始为空；
- 各建一条，互不可见；
- A 读取/修改/删除 B 的笔记均被拒绝，B 内容不变；
- A 访问会话/Agent 403，访问学习页被导向笔记页；
- 管理员重置 A 密码后，A 旧会话 401，B 会话仍有效；
- 重启服务后 A/B 账号和笔记仍在。

完整结果见桌面《Lamplit账号与笔记隔离首轮结果-2026-10-26.md》。
