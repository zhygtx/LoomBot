"""LoomBot 的 Python 侧运行时。

- `scanner/`：扫描插件包，产出节点目录
- `executor/`：工作流 DAG 执行器
- `adapter/`：适配器监管器（控制面 + 反向 WebSocket + 插件工作进程）

导入根是 `python/` 这一层：`python -m system.executor.main` / `python -m system.adapter.main`。
"""
