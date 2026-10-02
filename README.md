# Loom Frontend

这是 Loom 的新前端项目。旧 `web/` 暂时保留作交互与素材参考，新项目不继承旧前端架构。

## 本地运行

```bash
npm install
npm run dev
```

开发服务器默认使用 `http://localhost:5173`。默认将 `/api` 代理到 `http://localhost:8080`；如需修改，在本地 `.env.local` 中设置：

```dotenv
VITE_DEV_API_TARGET=http://localhost:8080
VITE_BACKEND_WS_ORIGIN=ws://localhost:8080
```

## 常用命令

```bash
npm run dev
npm run typecheck
npm run format
npm run build
```

技术决策和目录约束见 [docs/README.md](./docs/README.md)。
