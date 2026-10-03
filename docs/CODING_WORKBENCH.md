# 编程题工作台与桌面版边界

模拟面试的 `CODING` 题使用 Java 21 的 `public class Main`，通过标准输入和标准输出运行。用户可以反复修改代码和输入，再提交最终代码与思路。草稿暂存在当前浏览器标签页的 `sessionStorage`，提交后清除。

## 代码执行

Ardor 后端**不编译也不执行用户代码**。`POST /api/interviews/{sessionId}/run-code` 先检查登录用户拥有该面试，且题目是当前未回答的编程题，然后转发到独立的 [Piston Execute API](https://github.com/engineer-man/piston#execute-endpoint)。请求只允许 Java，限制源代码、标准输入、编译和运行时间及内存，并截断返回的输出。设置 `ARDOR_CODE_RUNNER_URL` 后启用；未配置时界面会显示“运行器尚未配置”。

**安全边界：** Piston 官方自托管 Docker 示例使用 `--privileged`。不要把它直接加入承载 PostgreSQL、Redis 和简历数据的 Ardor Compose，也不要给 Ardor 后端 Docker socket。面向其他用户开放前，应把运行器部署到独立主机或 VM，限制 Ardor 后端为唯一调用方，配置网络隔离、请求限流和观测。当前实现只是执行适配层；没有单独的隔离运行器时，不能声称已可运行代码。对公开服务还需验证 Java 运行时已安装、隔离失效处理与滥用压力测试。

## 桌面版

可以用 [Tauri 的 sidecar 机制](https://v2.tauri.app/develop/sidecar/) 把桌面窗口与本地进程打包，并通过 [Windows 安装包](https://v2.tauri.app/distribute/windows-installer/)发布到 GitHub Releases。但现有 Ardor 是 Next.js + Spring Boot + PostgreSQL + Redis 的多服务架构；仅把网页包进桌面窗口不会消除后端和数据服务依赖。

建议分两阶段：先做连接现有 Ardor 服务的桌面客户端；再设计真正免 Docker 的本地单用户模式，包括本地数据存储、任务队列、密钥保管、升级迁移和独立代码沙箱。不要把现有 PostgreSQL 数据直接转换或覆盖。桌面版与网页版可以共享 UI 和接口契约，但本地模式需要独立的部署与数据迁移设计。
