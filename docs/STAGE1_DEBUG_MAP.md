# Stage 1 请求追踪与 Debug 学习地图

根据 2026-09-07 当前工作区代码核对，只追踪 5 个业务接口。目标是亲眼看见：**HTTP 参数如何进入业务、数据如何改变、身份如何进入和离开一次请求**。

## 开始前：固定一次实验

- 在 IDEA 中 Debug 运行 `CampusBookingApplication`，程序参数填写 `--spring.profiles.active=dev`；基础服务按 [README](D:/Java_project/黑马点评/dianping/README.md) 启动。普通运行另一个 Maven/JAR 进程不会自动命中 IDEA 中的断点。
- 默认地址：`http://127.0.0.1:8081`。JSON 请求带 `Content-Type: application/json`。
- 用同一个开发手机号 `13812345678`。想观察首次注册，先确认它在 `campus_booking.account_user` 中不存在；已存在就换一个未使用的开发手机号，无需删数据。
- 下文 `C` 表示验证码响应中的 `data.devCode`，`T` 表示登录响应中的 `data.token`，`U` 表示 MySQL 实际生成的用户 ID，均用实际值替换。
- 验证码与登录请求先不带 Authorization；其他请求带 `Authorization: Bearer <T>`。
- 只启用正在追踪的请求所需断点。业务方法可 Step Into，Mapper、Redis 客户端和框架方法用 Step Over；断点所在行通常尚未执行，执行一步后再观察结果。
- **暂停 JVM 时，Redis 的 TTL（剩余有效时间）仍在减少。** 验证码默认 5 分钟有效、重发冷却 60 秒；暂停过久后失败，应重新取码。
- Watch 可观察 `UserContext.current()`。不要把 `consumeCode()`、`findAndRefresh()`、`deleteToken()` 等有副作用的方法放进 Watch 或反复 Evaluate：它们会消费验证码、续期或删除数据。

## 一次说明白的公共入口

这 5 条请求先由 Spring MVC 分发，再按 [WebConfig 中的注册顺序](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/config/WebConfig.java:21) 进入项目代码：

```text
HTTP
 → RefreshTokenInterceptor.preHandle：清空线程旧身份，读取 Authorization
 → LoginInterceptor.preHandle：仅受保护接口执行，要求当前身份存在
 → 解析/校验请求参数（有请求体时）
 → 对应 Controller → Service → Mapper / Redis
 → 返回 ApiResponse，由框架转换为 JSON
 → RefreshTokenInterceptor.afterCompletion：清理当前线程身份
```

`/code` 和 `/login` **只排除了 LoginInterceptor**，仍会经过 RefreshTokenInterceptor。不带 Token 时前者直接放行；若主动带了无效 Token，仍可能在到达 Controller 前被拒绝。

以下正常成功请求均返回 HTTP 200、`code="OK"`；每节只列有区别的 `data`。异常由 [ApiExceptionHandler.business()](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/common/ApiExceptionHandler.java:19) 转成对应状态与错误响应。首次追踪公共入口即可，后面不要重复进入框架源码。

## 1. 获取验证码：手机号如何变成 Redis 暂存数据

**HTTP：`POST http://127.0.0.1:8081/api/account/code`**

```json
{"phone":"13812345678"}
```

**从哪里进入：** RefreshTokenInterceptor 无 Token 放行 → 参数校验 → [AccountController.java:30](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:30) 的 `sendCode()`。

**完整调用链：**

```text
AccountController.sendCode(request)
 → AccountService.sendCode(phone)
   → 确认启用了开发短信模拟，生成随机 6 位 code
   → AccountRedisStore.issueCode(phone, code)
     → Redis 执行 issue-code.lua：检查冷却，写验证码和冷却 key
   ← Redis 1/0 转为 true/false
   ← CodeReceipt(deliveryMode, devCode, expiresInSeconds)
 ← ApiResponse.ok(receipt)
 → HTTP 200 JSON；afterCompletion 清理空的 UserContext
```

**状态变化：** 首次正常发送的前提是冷却 key 不存在。

| 存储位置 | 操作前 | 操作后 |
| --- | --- | --- |
| Redis Hash：`campus:account:code:13812345678` | 首次不存在 | `code=C`、`remaining=5`，TTL 约 300 秒 |
| Redis String：`campus:account:cooldown:13812345678` | 不存在 | 值 `"1"`，TTL 约 60 秒 |
| MySQL：`account_user` | 当前用户数据 | **没有查询或写入**，数据不变 |
| UserContext | `null` | 仍是 `null`，没有建立身份 |

若冷却 key 已存在，Lua 返回 0，不覆盖原验证码，接口返回 429/`CODE_RATE_LIMITED`。冷却结束后重新发送，会覆盖仍存在的旧验证码并重新设置验证码有效期。

**只打这 3 个断点：**

| 断点 | 重点观察 |
| --- | --- |
| [AccountController.java:30](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:30) | `request.phone()`；确认 JSON 已变成 Java 请求对象，而不是原始字符串 |
| [AccountRedisStore.java:29](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/auth/AccountRedisStore.java:29)，Lua 调用后 | `phone`、`code`、`result`。首次应为 1；重发受限时为 0。对照两个 Redis key 的实际字段和 TTL |
| [AccountService.java:49](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountService.java:49) | 返回的 `code` 与 Redis 中一致；`properties.codeTtl()`；`UserContext.current()` 仍为 null |

[issue-code.lua](D:/Java_project/黑马点评/dianping/src/main/resources/redis/issue-code.lua:2) 在 Redis 服务端执行，普通 Java 断点不能逐行进入。只读它的冷却判断和写入动作，用 Java 返回值与 Redis 前后状态验证。

**响应重点：** `data.deliveryMode="DEVELOPMENT_SIMULATION"`、`data.devCode=C`、`data.expiresInSeconds=300`。没有真实短信发送。

**Debug 后必须回答：** 为什么拿到验证码后，MySQL 还没有新增用户，而且当前请求也没有登录身份？

**无需重点阅读：** `CodeRequest`、`CodeReceipt` 的 record 语法和 `ApiResponse.ok()` 的包装；只确认字段与输入校验即可。

## 2. 登录：一次性验证码如何换成用户记录与 Token

**HTTP：`POST http://127.0.0.1:8081/api/account/login`**

```json
{"phone":"13812345678","code":"替换为实际的六位验证码"}
```

**从哪里进入：** RefreshTokenInterceptor 无 Token 放行 → 参数校验 → [AccountController.java:35](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:35) 的 `login()`。

**完整调用链：**

```text
AccountController.login(request)
 → AccountService.login(phone, code)
   → AccountRedisStore.consumeCode(phone, code)
     → Redis 执行 consume-code.lua，正确则删除验证码 key
   → AccountMapper.findByPhone(phone)
     → SELECT ... FROM account_user WHERE phone = ?
   → 仅不存在用户时：设置 STUDENT，AccountMapper.insert(account)
     → MySQL INSERT 提交，生成并回填 account.id
   → AccountRedisStore.createToken(UserIdentity)
     → 生成 token，Redis SET tokenKey "U:STUDENT"，TTL 30 分钟
   ← LoginResult(token, "Bearer", 1800, Profile)
 ← ApiResponse.ok(loginResult)
 → HTTP 200 JSON；afterCompletion 清理空的 UserContext
```

已有用户直接复用 MySQL 的 ID 和角色，不再次插入。以下状态以“首次登录的新手机号”为例。

| 存储位置 | 操作前 | 操作后 |
| --- | --- | --- |
| Redis：`campus:account:code:13812345678` | Hash 内有正确 C、剩余次数 | 验证成功后整个 key 被删除 |
| Redis：`campus:account:cooldown:13812345678` | 可能仍在冷却，也可能已自然过期 | 登录不修改它，不重置冷却时间 |
| MySQL：`account_user` | 无该手机号 | 新增用户 U；`role=STUDENT`，默认昵称 `同学5678` |
| Redis：`campus:account:token:<T>` | 不存在 | String 值为 `U:STUDENT`，TTL 约 1800 秒 |
| UserContext | `null` | **这次无 Token 的登录请求中仍为 null**；登录方法没有调用 `UserContext.set()` |

**只打这 4 个断点：**

| 断点 | 重点观察 |
| --- | --- |
| [AccountService.java:53](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountService.java:53) | `phone`、`code`。Step Over 一次 `consumeCode()`：成功才进入下一步；对照验证码 key 已删除。想看 Lua 调用可进入 Store 第 33 行，执行返回 1/0 后再转成布尔值，不重复 Evaluate |
| [AccountService.java:57](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountService.java:57)，查询之后 | `account`：首次为 null；已有用户是查出的对象。第一次登录会走创建分支 |
| [AccountService.java:64](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountService.java:64)，插入调用 | 执行前 `account.id=null`、`role=STUDENT`；Step Over 后观察自动回填的 `account.id=U`，并核对 MySQL 新记录 |
| [AccountRedisStore.java:38](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/auth/AccountRedisStore.java:38)，写入 Token | `identity.userId()`、`identity.role()`、`token`；执行后核对完整 key、字符串值和 TTL。返回 Service 后观察用户与 Token 如何进入 LoginResult |

`findByPhone()` 的 SQL 在 [AccountMapper:10](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/mapper/AccountMapper.java:10)；`insert()` 来自 MyBatis-Plus 的 BaseMapper，**不必追踪代理和第三方源码**。

只加一个失败对照：成功登录后，使用同一个验证码再登录。[consume-code.lua](D:/Java_project/黑马点评/dianping/src/main/resources/redis/consume-code.lua:2) 查不到验证码，返回 0；Service 第 54 行抛出异常，响应 400/`INVALID_CODE`，后面的 MySQL 查询断点不会命中。输错但尚未耗尽次数时，则减少 `remaining`；不创建 Token、不查 MySQL。

**响应重点：** 保存 `data.token=T`；观察 `data.user.id=U`、`role=STUDENT`。不要把响应根部 `code="OK"` 与请求里的验证码 `code` 混淆。

**Debug 后必须回答：** 为什么成功使用过的验证码再次登录，会在查询 MySQL 之前被拒绝？

**无需重点阅读：** Account 的 getter/setter、`Profile.from()`、LoginResult 包装、UUID 内部实现。重点是“先验证并消费 → 再保存用户 → 最后签发 Token”的先后顺序。

## 3. 查询 /me：Token 如何成为一次请求中的身份

**HTTP：`GET http://127.0.0.1:8081/api/account/me`**

请求头：`Authorization: Bearer <T>`。无请求体。

**从哪里进入：** [RefreshTokenInterceptor.java:24](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/auth/RefreshTokenInterceptor.java:24) 先恢复身份，LoginInterceptor 校验通过后，才到 [AccountController.java:40](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:40) 的 `me()`。

**完整调用链：**

```text
RefreshTokenInterceptor.preHandle
 → AccountRedisStore.findAndRefresh(token)
   → Redis GETEX tokenKey：读取 "U:STUDENT" 并续期
 ← UserIdentity(U, STUDENT)
 → request.setAttribute(TOKEN_ATTRIBUTE, token)
 → UserContext.set(user)
 → LoginInterceptor.preHandle → UserContext.require()
 → AccountController.me()
   → 从 UserContext 取 userId
   → AccountService.currentUser(userId)
     → requireAccount(userId)
       → AccountMapper.selectById(userId) → MySQL SELECT
     ← Account → Profile
   ← ApiResponse.ok(profile)
 → HTTP 200 JSON
 → RefreshTokenInterceptor.afterCompletion → UserContext.clear()
```

注意这里的 **Redis 查询发生在 Controller 之前**。Service 中的 `currentUser()` 查询的是 MySQL。

| 存储位置 | 操作前 | 操作后 |
| --- | --- | --- |
| Redis：`campus:account:token:<T>` | 值 `U:STUDENT`，TTL 持续减少 | 值不变，GETEX 执行时 TTL 重设为 1800 秒，此后继续倒计时 |
| MySQL：`account_user` | 用户 U 的资料 | 按 ID 查询，记录不变 |
| UserContext | 入口清理后为 null | Redis 命中后变成 `UserIdentity(U, STUDENT)`；请求结束再变回 null |

**这一条最重要，打 5 个断点：**

| 断点 | 重点观察 |
| --- | --- |
| [AccountRedisStore.java:44](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/auth/AccountRedisStore.java:44) | `token`；Step Over 后 `value` 应为 `"U:STUDENT"`，不是完整用户 JSON；观察 TTL 被续期。要比较前后，请稍等片刻再发请求 |
| [RefreshTokenInterceptor.java:27](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/auth/RefreshTokenInterceptor.java:27) | 执行前 `user` 已有身份，`UserContext.current()` 还是 null；Step Over 后上下文变为该身份 |
| [AccountController.java:40](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:40) | `UserContext.current().userId()`。请求体没有 userId，Controller 从上下文取得 U |
| [AccountService.java:95](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountService.java:95)，selectById 之后 | `userId`、`account` 的昵称/手机号/角色。返回资料来自这次数据库查询 |
| [RefreshTokenInterceptor.java:34](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/auth/RefreshTokenInterceptor.java:34) | 清理前上下文有 U；Step Over 后为 null。Controller 已返回不等于请求生命周期已经完成 |

可以在 [LoginInterceptor:12](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/auth/LoginInterceptor.java:12) **临时加一个断点**：无 Authorization 请求时，上下文为 null，401 发生在这里；此时 Controller 不会执行。

Token key 已过期或不存在时，Store 的 `value=null`，则更早在 RefreshTokenInterceptor 第 25 行返回对应 401 异常。不要把这两种拦截位置混为一谈。

**响应重点：** `data.id`、`phone`、`nickname`、`role`；不会再签发新的 Token。

**Debug 后必须回答：** 登录已返回 Token，为什么下一次请求仍要恢复 UserContext，而且返回资料后还要把它清空？

**无需重点阅读：** `Profile.from()` 的字段复制、BaseMapper 生成 SELECT 的内部过程、JSON 序列化源码。

## 4. 修改个人资料：身份决定改谁，请求体决定改什么

**HTTP：`PATCH http://127.0.0.1:8081/api/account/me`**

请求头：`Authorization: Bearer <T>`。

```json
{"nickname":"  校园同学  "}
```

**从哪里进入：** 与 GET /me 相同的身份恢复和登录校验 → 请求参数校验 → [AccountController.java:45](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:45) 的 `updateProfile()`。

**完整调用链：**

```text
RefreshTokenInterceptor → Redis GETEX、恢复 UserContext
 → LoginInterceptor 校验身份
 → AccountController.updateProfile(request)
   → UserContext.require().userId() 提供目标用户
   → AccountService.updateProfile(userId, nickname)
     → requireAccount(userId) → AccountMapper.selectById → MySQL SELECT
     → new Account changes：只设置 id 与去掉首尾空白后的 nickname
     → AccountMapper.updateById(changes) → MySQL UPDATE
     → 同步内存中 account 的 nickname，转换成 Profile
   ← ApiResponse.ok(profile)
 → HTTP 200 JSON
 → afterCompletion 清理 UserContext
```

| 存储位置 | 操作前 | 操作后 |
| --- | --- | --- |
| Redis：`campus:account:token:<T>` | 值 `U:STUDENT`，旧 TTL | 拦截器已续期；值仍是 `U:STUDENT`，没有新增昵称字段 |
| MySQL：`account_user` 的 U 行 | 昵称如 `同学5678` | 昵称为 `校园同学`；本例确实改值时数据库自动更新 `updated_at`；手机号、角色不变 |
| UserContext | 本请求开始时 null | 恢复为 U → 用于确定修改目标 → 请求结束清空 |

**复用 GET /me 的身份断点；业务只加 3 个：**

| 断点 | 重点观察 |
| --- | --- |
| [AccountController.java:45](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:45) | `request.nickname()` 仍含首尾空格；`UserContext.current().userId()` 决定修改谁，请求体没有目标 ID |
| [AccountService.java:83](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountService.java:83) | `userId`、`account`、`changes`；changes 只有 id、新昵称，phone/role 为 null。Step Over 后在数据库看 UPDATE 结果，确认没有覆盖原角色 |
| [AccountService.java:85](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountService.java:85) | 内存 `account.nickname` 已改为新值，响应由这个对象转换而来；这里没有再查一次数据库。随后再发 GET /me 验证持久化结果 |

参数边界只看一次：请求里额外加 `role` 或 `id` 会被当前 JSON 配置拒绝；全空白昵称也会返回 400。此时业务 Controller 不执行，但此前拦截器可能已完成 Token 续期，结束时仍要清理身份。

**响应重点：** `data.nickname="校园同学"`，用户 ID 和角色保持原值。

**Debug 后必须回答：** 为什么改完昵称后 Redis Token 的值没有改变，而下一次 GET /me 却能读到新昵称？

**无需重点阅读：** ProfileUpdate 的 record 语法、getter/setter、返回对象字段复制；但必须知道输入只有 nickname，以及 `changes` 只更新被设置的字段。

## 5. 退出：删除登录态与清理请求身份是两件事

**HTTP：`POST http://127.0.0.1:8081/api/account/logout`**

请求头：`Authorization: Bearer <T>`。无请求体。

**从哪里进入：** RefreshTokenInterceptor 先读取并续期 Token、恢复身份 → LoginInterceptor → [AccountController.java:50](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:50) 的 `logout()`。

**完整调用链：**

```text
RefreshTokenInterceptor → Redis GETEX、保存 request 的 token 属性、恢复 UserContext
 → LoginInterceptor 校验身份
 → AccountController.logout(request)
   → 从 UserContext 取得 U，从 request 属性取得 T
   → AccountService.logout(userId, token)
     → AccountRedisStore.deleteToken(token) → Redis DEL tokenKey
     → 记录退出日志
   ← ApiResponse.ok(null)
 → HTTP 200 JSON
 → afterCompletion → UserContext.clear()
```

| 存储位置 | 操作前 | 操作后 |
| --- | --- | --- |
| Redis：`campus:account:token:<T>` | 有效 Token | 先被拦截器续期，再被 Service 删除，最终不存在 |
| Redis：同用户其他 Token | 若另一次登录产生了其他 Token | 本次退出不操作它们 |
| MySQL：`account_user` | 用户 U 的记录存在 | 没有查询/删除；用户仍存在 |
| UserContext | 拦截器已恢复 U | DEL 后当前线程中仍有 U；直到 afterCompletion 才清空 |

**只打这 3 个断点：**

| 断点 | 重点观察 |
| --- | --- |
| [AccountController.java:50](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountController.java:50) | `UserContext.current()`；`request.getAttribute(RefreshTokenInterceptor.TOKEN_ATTRIBUTE)` 就是 T。Token 是拦截器传给 Controller 的 |
| [AccountService.java:89](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/AccountService.java:89) | `userId`、`token`；Step Over 后 Redis key 消失，但 `UserContext.current()` 仍是 U。不必进入 deleteToken 的单行包装 |
| [RefreshTokenInterceptor.java:34](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/account/auth/RefreshTokenInterceptor.java:34) | Step Over 后确认 UserContext 变成 null；区别于上一步删除 Redis 的动作 |

**响应重点：** `{"code":"OK","message":"操作成功","data":null}`。

退出后，使用原 T 再请求 GET /me：Store 第 44 行返回 null → RefreshTokenInterceptor 第 25 行拒绝 → 401/`UNAUTHORIZED`。这次不会到达 /me 的 Controller，也不会查询 MySQL；Redis key 不会因 GETEX 而被重新创建。

**Debug 后必须回答：** 删除 Token 后，为什么本次请求的 UserContext 暂时仍然有 U，但下次带同一个 Token 的请求会在 Controller 之前被拒绝？

**无需重点阅读：** deleteToken 单行委托、成功响应包装、日志格式。

## 推荐 Debug 顺序

1. **获取验证码**：只看请求参数 → Lua 返回值 → 两个 Redis key。保存 C。
2. **登录**：看验证码被消费 → 用户查找/创建 → ID 回填 → Token 写入。保存 T、U。
3. **GET /me**：完整看一遍“读取 Token → 续期 → 设置 UserContext → 查资料 → 清理 UserContext”。
4. **PATCH /me → 再 GET /me**：确认“修改对象由身份决定”“新昵称已落库”“Token 值不需要带昵称”。
5. **logout → 原 Token 再 GET /me**：确认 Redis key 删除、本请求身份清理、下一请求在拦截器中返回 401。

主链路走通后，只选一个异常复盘：**带有效 Token 提交全空白昵称**，在 [invalidInput()](D:/Java_project/黑马点评/dianping/src/main/java/com/campusbooking/common/ApiExceptionHandler.java:25) 和 afterCompletion 看“业务方法未执行，仍返回 400 并清理身份”。无需一次展开全部失败场景。

完成标准：能在每次暂停时指出“请求走到哪一层、下一步操作哪个 key/表、当前线程有没有身份、这次响应的数据从哪里来”，并用实际数据变化解释原因。

