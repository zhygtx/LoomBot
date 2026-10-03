"""PR 闸门。

四道闸门，顺序执行，任何一道不过就打回（并把原因评论到 PR 上）：

1. **改动范围**：只能改 `sources/`，且一次只能提交一个插件。
2. **规范分级**：不达标 → 打回；最低达标（可补全）→ 放行；最佳规范 → 放行。
3. **安全扫描**：`audit.py` 出现 HIGH → 打回（静态规则，不交给 AI 放宽）。
4. **AI 审核**：`BLOCK` → 打回；`WARN` / `OK` → 放行（只把建议写进评论）。

另有一条**删除 PR** 的旁路（见 `gate_deletion`）：作者删掉自己已发布的版本（`loom_publish.py remove`
生成的改动），机器人核对归属后合并，不发布新代码，也不走补全和安全扫描。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from . import audit
from .complete import PluginScan, scan_plugin

PLUGIN_MARKERS = ("nodes", "events", "actions")
INDEX_NAME = "index.json"
PLUGINS_DIR = "plugins"


@dataclass
class GateResult:
    """一道闸门的结果。"""

    passed: bool
    level: str = ""
    reasons: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def find_plugin_dirs(sources_root: Path) -> list[Path]:
    """找出 `sources/` 下所有「插件目录」。

    判据是**位置**：直接含 `nodes/` / `events/` / `actions/` 子目录的那个目录就是插件目录。
    这样作者写 `sources/<插件名>/` 还是 `sources/<账号>/<插件名>/` 都能认出来，
    机器人不用去猜目录层级。
    """
    found: list[Path] = []
    if not sources_root.is_dir():
        return found
    for path in sorted(sources_root.rglob("*")):
        if not path.is_dir() or path.name.startswith("."):
            continue
        if any((path / marker).is_dir() for marker in PLUGIN_MARKERS):
            found.append(path)
    return found


def gate_scope(changed_files: list[str], sources_root: Path) -> GateResult:
    """闸门 1：改动只限 sources/，一次一个插件。"""
    outside = [name for name in changed_files if not name.startswith("sources/")]
    if outside:
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=[
                "改动必须只落在 `sources/` 下，以下文件不在范围内："
                + "、".join(f"`{name}`" for name in outside[:10])
            ],
        )
    plugins = find_plugin_dirs(sources_root)
    if not plugins:
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=[
                "在 `sources/` 下没找到插件目录。"
                "插件至少要有一个 `nodes/`（工作流节点）、`events/` 或 `actions/`（适配器）目录。"
            ],
        )
    if len(plugins) > 1:
        listed = "、".join(f"`{path.relative_to(sources_root).as_posix()}`" for path in plugins)
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=[f"一次只能提交一个插件，这次发现 {len(plugins)} 个：{listed}"],
        )
    return GateResult(passed=True, level="OK", notes=[f"插件目录：`{plugins[0].name}`"])


def is_deletion_pr(changed_files: list[str], work_root: Path) -> bool:
    """这个 PR 是不是「只删已发布版本」的删除 PR。

    判据：改动只落在 `index.json` 和 `plugins/` 下，且 `plugins/` 下的文件在 PR 分支上已经不存在
    （Gitee 的文件接口不带 status，只能靠工作树里还在不在来判断是不是删除）。
    """
    names = [str(name or "").replace("\\", "/").strip() for name in changed_files]
    names = [name for name in names if name]
    if not names:
        return False
    touched_plugin = False
    for name in names:
        if name == INDEX_NAME:
            continue
        if not name.startswith(PLUGINS_DIR + "/"):
            return False
        if (work_root / name).exists():
            return False
        touched_plugin = True
    return touched_plugin


def _plugins_by_id(data: dict) -> dict[tuple[str, str], dict]:
    result: dict[tuple[str, str], dict] = {}
    for entry in (data or {}).get("plugins") or []:
        if not isinstance(entry, dict):
            continue
        ident = (str(entry.get("namespace") or ""), str(entry.get("key") or ""))
        result[ident] = entry
    return result


def _versions_by_name(entry: dict) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for item in (entry or {}).get("versions") or []:
        if isinstance(item, dict):
            result[str(item.get("version") or "")] = item
    return result


def gate_deletion(before: dict, after: dict, actor: str) -> GateResult:
    """删除 PR 的闸门：只能删自己名下（index.json 的 `owner`）的已发布版本，且只能删、不能加或改。

    `before` 是目标分支上的 index.json，`after` 是 PR 里的 index.json，都是解析好的对象。
    `actor` 是 PR 提交人账号。
    """
    before_plugins = _plugins_by_id(before)
    after_plugins = _plugins_by_id(after)
    actor_name = str(actor or "").strip()
    problems: list[str] = []
    foreign: list[str] = []
    removed = 0

    added_plugins = set(after_plugins) - set(before_plugins)
    if added_plugins:
        problems.append(
            "新增了插件：" + "、".join(f"`{ns}.{key}`" for ns, key in sorted(added_plugins))
        )

    for ident in sorted(set(before_plugins) & set(after_plugins)):
        before_entry = before_plugins[ident]
        after_entry = after_plugins[ident]
        label = f"`{ident[0]}.{ident[1]}`"
        before_top = {k: v for k, v in before_entry.items() if k != "versions"}
        after_top = {k: v for k, v in after_entry.items() if k != "versions"}
        if before_top != after_top:
            problems.append(f"{label} 改了插件本身的字段（删除 PR 只能删版本）")
            continue
        before_versions = _versions_by_name(before_entry)
        after_versions = _versions_by_name(after_entry)
        fresh = set(after_versions) - set(before_versions)
        if fresh:
            problems.append(f"{label} 新增了版本：" + "、".join(sorted(fresh)))
        for version in sorted(set(before_versions) & set(after_versions)):
            if before_versions[version] != after_versions[version]:
                problems.append(f"{label} v{version} 改了版本条目（删除 PR 只能删）")
        gone = set(before_versions) - set(after_versions)
        if gone:
            removed += len(gone)
            owner = str(before_entry.get("owner") or before_entry.get("namespace") or "")
            if owner and owner != actor_name:
                foreign.append(f"{ident[0]}.{ident[1]}")

    for ident in sorted(set(before_plugins) - set(after_plugins)):
        before_entry = before_plugins[ident]
        removed += len(_versions_by_name(before_entry))
        owner = str(before_entry.get("owner") or before_entry.get("namespace") or "")
        if owner and owner != actor_name:
            foreign.append(f"{ident[0]}.{ident[1]}")

    if problems:
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=["删除 PR 的改动不合法：", *problems],
        )
    if removed == 0:
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=[
                "这个 PR 没有删掉 `index.json` 里的任何已发布版本。",
                "删版本请用 `python scripts/loom_publish.py remove <作者>/<插件键> <版本>`，"
                "它会同时删掉版本目录和索引记录，然后把这个改动提上来。",
            ],
        )
    if foreign:
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=[
                "只能删除自己名下的插件：这个 PR 删了 "
                + "、".join(f"`{name}`" for name in foreign)
                + f"，但提交人是 `{actor_name or '（未知）'}`。",
                "要删别人的插件，请联系维护者直接改 `index.json`。",
            ],
        )
    return GateResult(
        passed=True,
        level="DELETE",
        notes=[f"删除 PR：`{actor_name or '（未知）'}` 删掉了 {removed} 个已发布版本"],
    )


def gate_spec(scan: PluginScan) -> GateResult:
    """闸门 2：规范分级。"""
    if scan.problems:
        return GateResult(passed=False, level="REJECT", reasons=scan.problems)

    # 适配器有两样东西没法自动生成，必须作者提供：入口文件、连接配置模式。
    # 连接配置模式描述的是用户在连接测试台里要填什么字段，只有作者知道。
    if scan.has_adapter:
        required = ("main.py", "connection-schema.json")
        missing_files = [name for name in required if not (scan.root / name).is_file()]
        if missing_files:
            listed = "、".join(f"`{name}`" for name in missing_files)
            return GateResult(
                passed=False,
                level="REJECT",
                reasons=[
                    f"适配器插件还需要作者提供：{listed}。",
                    "适配器的入口文件和连接配置模式（`connection-schema.json`，"
                    "里面要写 `x-direction`）没法自动生成——用户在连接测试台里填什么字段只有作者知道。",
                    "工作流节点插件（只放 `nodes/`）不需要这些，可以什么都不写。",
                ],
            )

    missing: list[str] = []
    for node in scan.nodes:
        tags = []
        if not node.declared:
            tags.append("缺装饰器")
        if node.needs_ctx:
            tags.append("缺 ctx")
        if not node.return_annotation:
            tags.append("缺返回值类型")
        usable = [p for p in node.params if p.name not in {"ctx", "conn", "frame", "params"}]
        if any(p.annotation is None for p in usable):
            tags.append("参数缺类型")
        if tags:
            missing.append(f"`{node.func_name}`（{'、'.join(tags)}）")

    if not missing:
        return GateResult(passed=True, level="BEST", notes=["所有节点都已符合最佳规范"])
    return GateResult(
        passed=True,
        level="COMPLETABLE",
        notes=[f"以下节点会由机器人补全框架内容：{'；'.join(missing)}"],
    )


def gate_audit(plugin_dir: Path) -> GateResult:
    """闸门 3：安全静态扫描。HIGH 一律拦——这是硬规则，不交给 AI 判断。"""
    findings = audit.scan_dir(plugin_dir)
    high = [item for item in findings if item["severity"] == "HIGH"]
    if high:
        reasons = [
            f"`{item['file']}:{item['line']}` {item['desc']}" for item in high[:10]
        ]
        return GateResult(passed=False, level="REJECT", reasons=["安全扫描发现高危项：", *reasons])
    medium = [item for item in findings if item["severity"] == "MEDIUM"]
    notes = [f"安全扫描通过（{len(medium)} 项中危，已放行）"] if medium else ["安全扫描通过"]
    return GateResult(passed=True, level="OK", notes=notes)


def gate_ai(reviewer, files: dict[str, str]) -> GateResult:
    """闸门 4：AI 审核。只有 BLOCK 才拦。"""
    if reviewer is None:
        return GateResult(passed=True, level="SKIPPED", notes=["AI 审核已关闭"])
    result = reviewer.review(files)
    verdict = result["verdict"]
    if verdict == "BLOCK":
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=["AI 审核判定为 BLOCK：", *result["reasons"]],
        )
    notes = [f"AI 审核：{verdict}"]
    notes.extend(f"建议：{item}" for item in result["suggestions"])
    if verdict == "WARN":
        notes.extend(f"提示：{item}" for item in result["reasons"])
    return GateResult(passed=True, level=verdict, notes=notes)


def read_plugin_sources(plugin_dir: Path) -> dict[str, str]:
    """把插件源码读成 {相对路径: 内容}，供 AI 审核和评论用。"""
    files: dict[str, str] = {}
    for path in sorted(plugin_dir.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(plugin_dir)
        if any(part in {"__pycache__", ".git", ".venv", "venv", "_deps", "data"} for part in relative.parts):
            continue
        try:
            files[relative.as_posix()] = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
    return files
