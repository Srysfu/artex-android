# 壳对后端做了哪些适配

ARTEX 的后端是为 Linux 服务器写的。要在 Android 上跑，光把它编译成
`GOOS=android` 不够 —— 进程模型、文件系统布局、网络栈、执行环境全不一样。

**这个仓库的后端源码和二进制与上游完全一致**（对应提交 `a05a886`），
一行没改。所有差异都在 Android 壳（`app/src/`）里解决，靠的是
启动参数、环境变量、目录布局和页面注入。

下面按「做了什么 → 为什么必须做」逐项列出。

---

## 1. 进程编排

**在哪**：`ArtExService.java`

Android 没有 systemd，也没法像服务器那样手动 `systemctl start`。整个启动
链路必须由一个前台服务串起来，而且顺序不能乱：

```
释放 assets
    ↓
初始化数据库（仅首次）
    ↓
启动 PostgreSQL ──→ 等 5432 端口就绪
    ↓
释放 py-wheels / 补齐核心工具
    ↓
启动后端 ──→ 等 8787 端口就绪
    ↓
WebView 打开 http://127.0.0.1:8787/login
```

### 为什么必须等端口

`postgres` 和 `artex` 都是**异步监听**的：进程起来了不等于端口能连。
直接进入下一步会拿到 connection refused。

壳的做法是轮询 `portReady()`：前 10 秒每秒一次，之后 2 秒一次，最多等 120 秒。

### 为什么后端要重试 30 次

PostgreSQL 崩溃恢复期间**会拒绝连接**，返回 SQLSTATE `57P03`
（"the database system is starting up"）。

如果后端连不上就退出，用户看到的是「启动失败」，实际上再等几秒就好。
所以 `startBackend()` 循环 30 次、每次间隔 2 秒，只在端口真就绪时才返回。

真失败时要等 60 秒才报错，所以状态行会显示「重新启动服务（第 N 次）」，
让用户知道不是在死等。

### 为什么不能让进程死

`artex` 挂了整个应用就等于废了。壳给两个子进程都挂了看护：
读输出流（防止 pipe buffer 塞满导致阻塞）、检测退出、自动重启。

---

## 2. 环境变量

**在哪**：`ArtExService.startBackend()` 和 `pbEnv()`

Linux 上有 `/etc/profile`、`/tmp`、`/usr/bin`，这些 Android 都没有。

| 变量 | 值 | 为什么 |
|---|---|---|
| `HOME` | `files/` | 应用私有目录才是可写的，`/root` 不存在 |
| `TMPDIR` | `files/tmp/` | **Android 没有 `/tmp`**。很多工具往这写临时文件 |
| `PATH` | `pgBin:tools:tools/ext:系统 PATH` | 后端用 `bash -c` 调工具，靠 PATH 按名字找 |
| `PYTHONHOME` | `p/py` | Python 不在标准位置 |
| `PYTHONPATH` | `data/tools/py-socks` | 见下方说明 |
| `LD_LIBRARY_PATH` | `miscLib:pgLib` | 后端的动态依赖和 PG 的库都在私有目录 |
| `SSL_CERT_FILE` | `p/etc/tls/cert.pem` | Android 的 CA 路径和 Linux 不同 |
| `ARTEX_DNS` | 系统当前 DNS | 见第 3 节 |
| `GOMEMLIMIT` | `160MiB` | 手机内存有限，不限的话 GC 会放任堆涨到 OOM |
| `GOGC` | `60` | 比默认 100 更激进，用 CPU 换内存 |

### PYTHONPATH 只放 py-socks

venv 里的包（impacket / pypykatz / laZagne）**故意不进 `PYTHONPATH`**。

原因：这些包会带来自己的 `urllib3`、`requests` 副本，混进 import 路径
会覆盖 pip 自带的 vendored 依赖，pip 直接崩。这些工具是通过 `bin/` 下的
入口脚本调用的，不需要在全局 import 路径里。

### 绝不注入 HTTP_PROXY

早期版本集成了 TOR，会设 `HTTP_PROXY=http://127.0.0.1:9050`。
现在移除了 TOR —— 如果还留着这个变量而后端出站时 9050 没在监听，
**所有网络请求都会失败**，且报错信息完全看不出原因。

代码里专门留了注释警告这一点。

---

## 3. DNS 解析

**在哪**：`ArtExService.resolveDnsServers()`

Go 的 `net.Resolver` 在 Linux 上读 `/etc/resolv.conf`。Android 上：

- `/etc/resolv.conf` 不存在
- DNS 服务器是**每个网络动态分配**的，由 `ConnectivityManager` 管理

不处理的话，后端的所有域名解析都失败 —— 表现是「所有工具都连不上网」。

壳从 `LinkProperties.getDnsServers()` 读出当前网络的 DNS，v4 和 v6 分开收集，
拼成 `ARTEX_DNS` 注入给后端。

**两个坑**：

```java
// 回环地址喂给 Go 就是死循环
if (a.isLoopbackAddress()) continue;
```

有些 ROM 会在列表里塞 `127.0.0.1`，如果原样传给 Go 的 resolver，
它会一直查自己，最终超时。

---

## 4. 目录布局

**在哪**：`ArtExService` 的路径常量

PostgreSQL 是**编译时写死路径**的。Termux 版的 PG 硬编码：

```
/data/data/com.termux/files/usr
```

而 Android 应用的真实私有目录是：

```
/data/user/0/com.artex.mobile
```

### 为什么要用单字符目录名 `p`

不能简单改路径，因为 PG 二进制里的路径是**定长字符串**，
`patch_pg_paths.py` 只能做**等长替换**。

```
原路径  /data/data/com.termux/files/usr        31 字符
新路径  /data/user/0/com.artex.mobile/p        31 字符
```

关键在两点：

1. 把 PG 装到 `filesDir` 的**父目录**（`/data/user/0/<pkg>/`）而不是
   `filesDir` 里面 —— 这样前缀短
2. 目录名缩成单字符 `p` —— 凑够 31 字符

于是可以按长度降序做等长替换，不用重写二进制结构。

### 完整布局

```
/data/user/0/com.artex.mobile/
├── p/                       ← PG 安装位置（31 字符，等长替换的目标）
│   ├── postgres  initdb  psql  pg_ctl
│   ├── lib/                 ← 52MB，含 ICU 库
│   ├── share/
│   ├── py/                  ← Python（PYTHONHOME）
│   └── data/                ← PG 数据目录
├── files/
│   ├── artex                ← 后端二进制
│   ├── tmp/                 ← TMPDIR
│   └── artex/
│       ├── data/
│       │   ├── tools/       ← 核心工具
│       │   └── tools/ext/   ← 用户导入的工具
│       ├── skills/
│       └── packaging/
└── (Android 系统目录)
```

---

## 5. 工具注入

**在哪**：`ToolImporter.java`、`ArtExService.buildPath()`

后端执行工具的方式是固定的：

```go
func shellCmd() (string, []string) { return "bash", []string{"-c"} }
```

也就是说它**不检查工具是否存在**，直接 `bash -c "nuclei -u ..."`。
所以工具必须在 `PATH` 里，且必须可执行。

### 为什么必须内置 bash

Android 自带的是 `mksh` 的 `sh`，没有 `bash`。后端写死了 `bash`，
所以 `assets/bash/` 里带了一个 Android 编译版的 bash（含 readline /
ncurses / iconv 依赖）。

**这是硬依赖**，没有 bash 一个工具都调不动。

### 导入时的三重校验

用户从文件管理器选的二进制不能直接扔进 PATH：

**1. ELF 魔数 + 架构**

```
magic == 0x7F 'E' 'L' 'F'
e_machine == 0xB7    // AArch64
```

**2. 动态链接器检查**

```
读 PT_INTERP 段的字符串：
  ld-linux-*        → 拒绝（glibc，Android 没有）
  /system/ 开头     → 放行（Android 的 linker64，bionic）
  其它              → 拒绝
```

这一条是必需的：很多工具是静态编译的，但**动态链接 bionic 的也能跑**
（比如 `gogo` 就是 `/system/bin/linker64`）。只看「是否动态链接」
会误杀。

**3. 试跑探测**

依次试 `-version` / `--version` / `version` / `-h` / `--help`，
超时 1.8 秒：

```
start() 抛异常      → 不可用（可能是 SELinux 或架构问题）
有输出 + 有版本号   → 可用，记录版本
有输出 + 无版本号   → 继续试下一个参数
无输出              → 继续试
超时未退出          → 视为可用（有些工具 -h 会进交互）
全试完无输出        → 不可用
```

版本号提取还要过滤掉：

```
ANSI 色码
纯数字
help 文本（display / show）
错误信息（unknown / error / flag: / invalid / flag provided / not defined）
交互提问（"... (y/N)"）
```

否则 `ffuf` 的 `flag provided but not defined: -version` 会被当成版本号。

---

## 6. 页面适配

**在哪**：`MainActivity` 注入的 JS/CSS

后端前端是 Next.js 的**桌面版**，直接塞进 WebView 有 7 个问题。
壳在 `onPageFinished` 时注入一段 CSS/JS 修正。

| 问题 | 原因 | 修法 |
|---|---|---|
| 长 URL 顶破容器 | URL/payload/哈希不自动换行 | `overflow-wrap:anywhere`，`pre/code` 除外 |
| 侧边面板溢出到屏幕外 | 上游写了 `min-width:480px`，手机只有 412px | `@media(max-width:700px)` 下改成 `width:100%` |
| 移动端侧边栏点完不收 | 上游关成 Sheet 且隐藏了关闭按钮 | 发 Escape，160ms 后补点关闭按钮 |
| 表格被裁 | 容器 `overflow:hidden` | 检测 `scrollWidth > clientWidth` 改成 `auto` |
| 高度塌陷 | `.h-svh` 在老 WebView 上不支持 | 兜底成 `100vh` |
| 工具卡片描述被截断 | 上游用了 `line-clamp-1` | 放开成 `display:block` |
| 顶部栏太宽 | 桌面布局的 padding | 收紧 gap 和 padding |

### 溢出检测的性能问题

最初的实现是 `setInterval(1000)` 每秒全文档扫一遍。每次读 `scrollWidth`
都会**强制同步布局（reflow）**，文档里几百个 div 时滚动会明显发涩。

改成：

```javascript
MutationObserver 监听 DOM 变化
    ↓
停止变化 400ms 后才扫（防抖）
    ↓
距离上次扫描不足 1.5s 就跳过（节流）
```

DOM 不动就不扫。

### 一个踩过三次的坑

工具卡片描述的 CSS 最初被放进了 `if(!CSS.supports('height','100svh'))`
的分支里。现代 WebView **都支持 svh**，所以整个分支被跳过，
连续三个版本「修复」都没生效。

现在这段无条件执行，代码里留了注释警告。

---

## 7. Skill 注入

**在哪**：`SkillCatalog.java`

Skill 是后端的概念，壳不自己解压到目录 —— 那样后端不会重新加载。
走的是后端已有的 HTTP API：

```
POST   /api/skills/upload?overwrite=true    multipart，字段名 file
DELETE /api/skills/{name}
GET    /api/skills
```

认证从 WebView 的 Cookie 里取：

```java
CookieManager.getCookie("http://127.0.0.1:8787")
    → 解析出 artex_token
    → 作为 Bearer 头
```

**注意**：上传成功返回 **201**，删除返回 **200/204**。
早期版本只认 200，导致「后端拒绝 HTTP 201」这个假错误。

---

## 8. 数据备份与恢复

**在哪**：`BackupManager.java`、`RestoreManager.java`

Android 上没有 `pg_dump`（PG 只编译了核心二进制）。所以备份是**直接拷数据目录**。

这样可行是因为：备份和恢复的两端 PG 版本必然一致（同一个 APK）。

### 两种模式

| 模式 | 内容 | 大小 |
|---|---|---|
| 完整 | 数据库 + 全部任务文件 | 200MB+ |
| 精简 | 跳过任务工作目录（下载的样本、解包结果占大头） | 30MB |

### 恢复的原子性

恢复前先把当前状态打包成回滚点。解压失败就还原回去，
不会留下一个「数据库损坏但备份也不见了」的状态。

恢复后必须**重启服务**，否则后端还连着旧的数据库文件。

---

## 附：一键更新为什么用不了

后端自带的「检查更新 / 立即更新」在手机上无法完成最后一环。

**它怎么工作的**：

```
检查 GitHub Release → 下载新二进制 → 替换自己 → 退出进程
                                                    ↓
                                        守护脚本把它拉起来
```

最后一步依赖 `start.sh`（Windows 是 `start.bat`）这个 shell 守护循环。
后端前端里的提示原文就是这么写的：

> 一键更新依赖守护脚本重启程序。请通过 start.sh 启动 ARTEX；
> 直接运行 artex 本体时，程序退出后不会被自动拉起。

**本应用的情况**：后端由 `ArtExService` 用 `ProcessBuilder` 直接拉起，
外面没有守护脚本。所以更新完进程一退，服务就断了。

**现在怎么更新**：换 APK。新版本的 `assets/backend/artex` 会随
`ASSETS_VERSION` 递增被重新释放，等价于换了个后端。

**能不能补上**：可以。在 `ArtExService` 里把「后端退出」当成正常的
重启信号（现在已经在做，见第 1 节的重试逻辑），只是需要一个「这是更新
导致的退出，重开时用新二进制」的标记。工作量不大，但目前没做。

---

## 附：targetSdk 为什么必须是 27

这是**最硬的约束**，改不了。

Android 10（API 29）起禁止应用执行私有目录下的可执行文件：

```
avc: denied { execute } for path="/data/user/0/<pkg>/files/artex"
```

而本应用的所有可执行文件 —— 后端、PostgreSQL、Python、bash、全部工具 ——
都在私有目录里。

`targetSdk=27` 让系统按旧规则处理，才能执行。

**副作用**：拿不到一些新 API，也没法上架 Google Play（已停止接受
targetSdk < 31 的新应用）。这个项目本来也不上架。

---

## 附：包名为什么不能改

Python 的二进制里硬编码了：

```
/data/user/0/com.artex.mobile/p/lib
```

改包名就得重新编译 Python，并把所有 `.so` 的 rpath / interp 一起改。
PG 的路径补丁（第 4 节）也依赖这个长度。

**结论**：`com.artex.mobile` 是常量。
