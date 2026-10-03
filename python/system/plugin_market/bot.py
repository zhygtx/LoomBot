#!/usr/bin/env python3
"""Loom 插件库 PR 机器人（纯标准库）。

轮询 Gitee 开放 PR → 拉分支 → 四道闸门 → 通过就合并 + 补全 + 发布，不通过就评论打回。

设计要点（和 Loom 的库格式对齐）：

- **最低规范只有一条：函数放在对的目录里。** 没装饰器、没类型、没 plugin.toml 都能过，
  由 `complete.py` 补全；只有「位置放错 / 适配器签名不对」才打回。
- **补全发生在合并之后、冻结之前。** 机器人一般推不进贡献者的 fork，所以不在 PR 分支上改代码，
  而是合并到 main 之后再补全，补全结果连同冻结一起提交，并在 PR 里评论补了什么。
- **作者信息全自动**：author 取 Gitee 提交人账号，key 取插件目录名，版本没写就自动（首次 0.1.0，
  已有版本则 patch +1）。
- **自己管一份工作副本**：在 `<work_dir>/repo` 里克隆插件库，不碰 Loom 自己那份
  （`python/plugins/<库>/repo`）——否则两边都在同一个 checkout 上 pull/commit 会打架。

用法（在主项目的 `python/` 目录下）：

  python -m system.plugin_market.bot --once                        # 跑一轮（Java 定时任务就是这么调的）
  python -m system.plugin_market.bot                               # 常驻轮询
  python -m system.plugin_market.bot --test-review <目录> --no-ai   # 本地干跑，不联网

配置分两处：

- **非密钥**（仓库坐标、分支、gate 开关、AI 地址与模型）来自 `--config <json>`，或环境变量
  `LOOM_PLUGIN_MARKET_CONFIG`——Java 的定时任务把 `sys_config` 里的值序列化后传进来。
- **密钥**（Gitee token、AI key）**只从本地文件读**，默认 `python/secrets/plugin-market.json`，
  不进数据库、不进接口、不进日志。
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from datetime import date, datetime
from pathlib import Path

from system.plugin_market import config as market_config
from system.plugin_market.ai import AiClient, AiInferencer, AiError, AiReviewer
from system.plugin_market.complete import (
    HeuristicInferencer,
    complete_plugin,
    scan_plugin,
    write_manifest,
)
from system.plugin_market.gates import (
    GateResult,
    find_plugin_dirs,
    gate_ai,
    gate_audit,
    gate_deletion,
    gate_scope,
    gate_spec,
    is_deletion_pr,
    read_plugin_sources,
)
from system.plugin_market.gitee import GiteeClient, GiteeError
from system.plugin_market.gitee import authenticated_url, parse_repo_url

# demo/python —— 跑 `-m system.plugin_market.loom_publish` 时的 cwd
PY_ROOT = Path(__file__).resolve().parents[2]
# 本次运行的插件库工作副本，在 main() 里按配置定下来
WORK: Path | None = None
# 本地密钥（Gitee token、AI key），在 main() 里读一次；不进日志、不进仓库
SECRETS: dict = {}
# 终态：不用再管了
FINAL_STATUSES = {"published", "rejected", "merged_no_publish"}
# 中间态：合并已经做完了，只剩发布没走完——重跑时直接续发布，别再走一遍审核和合并
RESUMABLE_STATUSES = {"merged", "publish_failed"}


def _resumable(state: "State") -> list[tuple[int, dict]]:
    """状态里还没收尾的条目。这些 PR 已经合并、不在开放列表里了，得单独捞出来。"""
    result: list[tuple[int, dict]] = []
    for key, item in state.data.items():
        if not isinstance(item, dict) or item.get("status") not in RESUMABLE_STATUSES:
            continue
        try:
            result.append((int(key), item))
        except ValueError:
            continue
    return result


# ---------------------------------------------------------------------------
# 日志与状态
# ---------------------------------------------------------------------------


class BotLogger:
    """控制台 + 文件双输出，文件按天轮转、按 log_days 清理。"""

    def __init__(self, work_dir: Path, log_days: int) -> None:
        work_dir.mkdir(parents=True, exist_ok=True)
        self.path = work_dir / "auto_pr_bot.log"
        self.log_days = log_days
        self._roll()

    def _roll(self) -> None:
        if not self.path.exists():
            return
        stamp = datetime.fromtimestamp(self.path.stat().st_mtime).date()
        if stamp >= date.today():
            return
        archived = self.path.with_name(f"{self.path.name}.{stamp.isoformat()}")
        self.path.replace(archived)
        self._prune()

    def _prune(self) -> None:
        cutoff = date.today().toordinal() - self.log_days
        for item in self.path.parent.glob(f"{self.path.name}.*"):
            suffix = item.name.rsplit(".", 1)[-1]
            try:
                stamp = date.fromisoformat(suffix).toordinal()
            except ValueError:
                continue
            if stamp < cutoff:
                item.unlink(missing_ok=True)

    def log(self, message: str) -> None:
        line = f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} {message}"
        print(line, flush=True)
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")


class State:
    """记录处理过的 PR，避免重复处理；新提交（head sha 变了）会自动重审。"""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.data: dict[str, dict] = {}
        if path.is_file():
            try:
                loaded = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    self.data = loaded
            except ValueError:
                self.data = {}

    def get(self, number: int) -> dict | None:
        return self.data.get(str(number))

    def set(self, number: int, status: str, sha: str, note: str = "") -> None:
        self.data[str(number)] = {
            "status": status,
            "sha": sha,
            "note": note,
            "at": datetime.now().isoformat(timespec="seconds"),
        }
        self.path.write_text(
            json.dumps(self.data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )


class PidLock:
    """单实例保护；陈旧锁自动接管。"""

    def __init__(self, path: Path) -> None:
        self.path = path

    def acquire(self) -> None:
        if self.path.is_file():
            try:
                pid = int(self.path.read_text(encoding="utf-8").strip())
            except ValueError:
                pid = 0
            if pid and _alive(pid):
                raise SystemExit(f"已经有一个实例在跑（pid={pid}），退出")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(str(_current_pid()), encoding="utf-8")

    def release(self) -> None:
        self.path.unlink(missing_ok=True)


def _current_pid() -> int:
    import os

    return os.getpid()


def _alive(pid: int) -> bool:
    import os

    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


# ---------------------------------------------------------------------------
# git
# ---------------------------------------------------------------------------


def git(*args: str, cwd: Path | None = None) -> str:
    """在工作副本里跑 git。`WORK` 在 main() 里按配置定下来。"""
    target = cwd or WORK
    if target is None:
        raise RuntimeError("插件库工作副本还没确定")
    result = subprocess.run(
        ["git", *args], cwd=str(target), capture_output=True, text=True, encoding="utf-8"
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败：{(result.stderr or result.stdout).strip()}")
    return result.stdout


def repo_is_clean() -> bool:
    return not git("status", "--porcelain").strip()


# ---------------------------------------------------------------------------
# 每个 PR 的处理
# ---------------------------------------------------------------------------


def next_version(root: Path, author: str, key: str) -> str:
    """作者没写版本号时自动定：首次 0.1.0，已有版本则 patch +1。"""
    published = root / "plugins" / author / key
    if not published.is_dir():
        return "0.1.0"
    versions = sorted(
        (path.name for path in published.iterdir() if path.is_dir()),
        key=lambda name: tuple(int(part) if part.isdigit() else 0 for part in name.split(".")),
    )
    if not versions:
        return "0.1.0"
    parts = versions[-1].split(".")
    while len(parts) < 3:
        parts.append("0")
    try:
        patch = int(parts[2]) + 1
    except ValueError:
        patch = 1
    return f"{parts[0]}.{parts[1]}.{patch}"


def normalize_layout(plugin_dir: Path, sources_root: Path, account: str) -> Path:
    """把插件目录归一到 `sources/<账号>/<插件名>/`。作者写错层级也没关系。"""
    target = sources_root / account / plugin_dir.name
    if plugin_dir.resolve() == target.resolve():
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        shutil.rmtree(target)
    shutil.move(str(plugin_dir), str(target))
    # 清掉搬空后的空壳目录
    parent = plugin_dir.parent
    while parent != sources_root and parent.is_dir() and not any(parent.iterdir()):
        parent.rmdir()
        parent = parent.parent
    return target


def fix_manifest_identity(plugin_dir: Path, account: str, key: str) -> bool:
    """把 plugin.toml 里的 key / author 改成权威值（目录名与账号）。

    身份字段不是语义，是 Loom 用来拼 plugin_key 的：作者写成昵称（`张三`）而账号是 `zhangsan`
    时，check 会直接报"作者目录名不一致"。机器人顺手改掉，作者不用管这层。
    只替换这两行，其余内容和注释原样保留。
    """
    path = plugin_dir / "plugin.toml"
    if not path.is_file():
        return False
    original = path.read_text(encoding="utf-8")
    updated = original
    for field, value in (("key", key), ("author", account)):
        pattern = re.compile(rf"^(\s*{field}\s*=\s*).*$", re.MULTILINE)
        updated = pattern.sub(
            lambda match: match.group(1) + json.dumps(value, ensure_ascii=False), updated
        )
    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


def process_pull(client: GiteeClient, config: dict, state: State, log: BotLogger, pull: dict) -> None:
    number = int(pull["number"])
    head_sha = str((pull.get("head") or {}).get("sha") or "")
    previous = state.get(number)
    if previous and previous.get("sha") == head_sha:
        if previous.get("status") in FINAL_STATUSES:
            return
        if previous.get("status") in RESUMABLE_STATUSES:
            # 上次已经合过了，只是发布没走完。重走审核和合并只会失败（PR 已经合并/关闭），
            # 所以直接续发布这一步。
            log.log(f"PR #{number} 续做发布（上次状态：{previous.get('status')}）")
            _publish_and_report(client, config, state, log, number, pull, head_sha)
            return

    # 工作区不干净就别动 git：切分支会覆盖未提交的改动，发布那一步也会因此拒绝执行。
    if not repo_is_clean():
        log.log(f"PR #{number} 跳过：工作区有未提交的改动，先处理掉再跑")
        return

    log.log(f"PR #{number} 开始处理：{pull.get('title') or ''}")
    files = [str(item.get("filename") or "") for item in client.list_pull_files(number)]
    deletion = False

    # 拉到本地分支看代码（只读，不往 PR 分支写东西）
    branch = f"pr-{number}"
    git("fetch", "--depth", "1", "origin", f"pull/{number}/head")
    git("checkout", "-B", branch, "FETCH_HEAD")
    try:
        # 删除 PR 和普通 PR 的分流要在拉下来之后做：判据是 plugins/ 下的文件在分支上还在不在。
        deletion = is_deletion_pr(files, WORK)
        if deletion:
            result = _review_deletion(config, log, pull, files)
        else:
            result = _review_pull(client, config, log, number, files)
    finally:
        git("checkout", config["repo"]["branch"])

    if not result.passed:
        body = "## 自动审核未通过\n\n" + "\n".join(f"- {item}" for item in result.reasons)
        body += "\n\n改完直接往这个 PR 推新提交，机器人会自动重审。"
        client.comment(number, body)
        state.set(number, "rejected", head_sha, "；".join(result.reasons)[:400])
        log.log(f"PR #{number} 打回")
        return

    client.comment(number, _deletion_plan_comment(result) if deletion else _plan_comment(result))
    if not config["gates"].get("auto_merge", True):
        state.set(number, "reviewed", head_sha, "只审不合（auto_merge=false）")
        log.log(f"PR #{number} 通过审核，但 auto_merge=false，未合并")
        return

    # 有些仓库开了「审查通过才能合并」。机器人就是审核方，所以合并前先自己批一下。
    if config["gates"].get("approve_before_merge", True):
        try:
            client.approve(number, "自动审核通过，准备合并并发布。")
        except GiteeError as exc:
            log.log(f"PR #{number} 标记审查通过失败（继续尝试合并）：{exc}")

    try:
        client.merge(number, method=config["gates"].get("merge_method", "squash"))
    except GiteeError as exc:
        # 合并被仓库设置挡住时，光看 405 看不出要改什么，直接把排查方向写出来。
        client.comment(
            number,
            "## 审核通过，但合并被仓库设置挡住\n\n"
            f"```\n{exc}\n```\n\n"
            "常见原因：仓库的「Pull Requests → 合并前需要」勾了**审查通过**或**测试通过**。\n"
            "- 审查通过：机器人合并前会自己批一次，一般不会卡；\n"
            "- 测试通过：仓库没配 CI 的话这项永远满足不了，需要去仓库设置里取消勾选。\n",
        )
        raise
    log.log(f"PR #{number} 已合并")

    if deletion:
        # 删除 PR 只是删掉已发布版本，没有代码要冻结发布；合并即完成。
        # 拉一下目标分支，让后面的 PR 拿到的 before 是最新的。
        git("fetch", "origin", config["repo"]["branch"])
        state.set(number, "merged_no_publish", head_sha, "删除 PR（作者撤下自己的版本）")
        log.log(f"PR #{number} 删除已生效")
        return

    state.set(number, "merged", head_sha)

    if not config["gates"].get("auto_publish", True):
        state.set(number, "merged_no_publish", head_sha, "只合不发（auto_publish=false）")
        return
    _publish_and_report(client, config, state, log, number, pull, head_sha)


def _publish_and_report(
    client: GiteeClient, config: dict, state: State, log: BotLogger, number: int, pull: dict, head_sha: str
) -> None:
    """发布 + 把结果写回 PR 和状态。发布失败标成可重试，别用终态把它盖死。"""
    try:
        published = _publish(config, log, number, pull)
    except Exception as exc:  # noqa: BLE001 - 发布失败要留痕并让作者知道
        log.log(f"PR #{number} 发布失败：{exc}")
        client.comment(
            number,
            f"## 已合并，但自动发布失败\n\n```\n{exc}\n```\n\n下一轮轮询会自动重试。",
        )
        state.set(number, "publish_failed", head_sha, f"发布失败：{exc}"[:400])
        return
    client.comment(number, published)
    state.set(number, "published", head_sha)
    log.log(f"PR #{number} 已发布")


def _review_pull(client, config, log: BotLogger, number: int, files: list[str]):
    sources_root = WORK / "sources"
    scope = gate_scope(files, sources_root)
    if not scope.passed:
        return scope
    plugins = find_plugin_dirs(sources_root)
    plugin_dir = plugins[0]
    scan = scan_plugin(plugin_dir)
    spec = gate_spec(scan)
    if not spec.passed:
        return spec
    safety = gate_audit(plugin_dir)
    if not safety.passed:
        return safety
    reviewer = _reviewer(config, log)
    ai = gate_ai(reviewer, read_plugin_sources(plugin_dir))
    if not ai.passed:
        return ai
    return GateResult(
        passed=True,
        level=spec.level,
        notes=[*scope.notes, *spec.notes, *safety.notes, *ai.notes],
    )


def _review_deletion(config: dict, log: BotLogger, pull: dict, files: list[str]):
    """删除 PR 的审核：作者撤下自己的已发布版本，机器人核对归属。"""
    if not is_deletion_pr(files, WORK):
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=["删除 PR 只能删 `plugins/` 下已发布的版本目录，不能加或改别的文件。"],
        )
    branch = config["repo"]["branch"]
    try:
        before_raw = git("show", f"origin/{branch}:index.json")
    except RuntimeError as exc:
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=[f"读不到目标分支上的 `index.json`：{exc}"],
        )
    try:
        before = json.loads(before_raw)
    except ValueError as exc:
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=[f"目标分支上的 `index.json` 不是合法 JSON：{exc}"],
        )
    try:
        after = json.loads((WORK / "index.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return GateResult(
            passed=False,
            level="REJECT",
            reasons=[f"PR 里的 `index.json` 读不了：{exc}"],
        )
    actor = str((pull.get("user") or {}).get("login") or "").strip()
    result = gate_deletion(before, after, actor)
    if result.passed:
        log.log(f"删除 PR 审核通过：actor={actor}")
    return result


def _plan_comment(result) -> str:
    lines = ["## 自动审核通过", "", f"规范等级：`{result.level}`", ""]
    lines.extend(f"- {item}" for item in result.notes)
    lines.append("")
    lines.append("接下来机器人会合并、补全框架内容并发布，补全结果会在下面再评论一次。")
    return "\n".join(lines)


def _deletion_plan_comment(result) -> str:
    lines = ["## 删除审核通过", "", f"审核结论：`{result.level}`", ""]
    lines.extend(f"- {item}" for item in result.notes)
    lines.append("")
    lines.append(
        "接下来机器人会合并这个 PR。Loom 侧会在下一个同步周期（默认 30 秒）拉到，"
        "引用被删版本的工作流会被标失效并摘掉触发。"
    )
    return "\n".join(lines)


def _publish(config: dict, log: BotLogger, number: int, pull: dict) -> str:
    """合并之后：补全 → 冻结 → 清理 sources → 提交推送。

    这一步**可重入**：推送是最容易失败的一环（网络抖动），失败后重跑不该从头再做一遍——
    那时候 sources 已经清空了，重做只会报「找不到插件」。所以先看本地有没有没推出去的发布提交。
    """
    branch = config["repo"]["branch"]
    git("checkout", branch)
    git("pull", "--ff-only", "origin", branch)
    if not repo_is_clean():
        raise RuntimeError("工作区不干净，拒绝发布（先让维护者处理）")

    account = str((pull.get("user") or {}).get("login") or "").strip()
    if not account:
        raise RuntimeError("拿不到 PR 提交人账号，无法确定 namespace")

    sources_root = WORK / "sources"
    plugins = find_plugin_dirs(sources_root)
    if not plugins:
        if _ahead_of_remote(branch):
            log.log("  本地已有未推送的发布提交，直接补推")
            _push(config, branch)
            return "## 已合并并发布\n\n上一次推送失败，这次补推成功。"
        if _head_is_publish_commit():
            return "## 已合并并发布\n\n这个 PR 之前已经发布过了，不需要重复发布。"
        raise RuntimeError("sources/ 下应该恰好一个插件，实际 0 个")
    if len(plugins) != 1:
        raise RuntimeError(f"sources/ 下应该恰好一个插件，实际 {len(plugins)} 个")
    plugin_dir = normalize_layout(plugins[0], sources_root, account)
    key = plugin_dir.name

    inferencer = _inferencer(config, log)
    scan, changed = complete_plugin(plugin_dir, inferencer)
    if scan.problems:
        raise RuntimeError("补全后仍然不达标：" + "；".join(scan.problems))
    for item in changed:
        log.log(f"  PR #{number} 补全 {item}")

    manifest = plugin_dir / "plugin.toml"
    if not manifest.is_file():
        events_dir, actions_dir = _adapter_dirs(plugin_dir)
        write_manifest(
            plugin_dir,
            key=key,
            author=account,
            version=next_version(WORK, account, key),
            name=_display_name(inferencer, scan),
            description=_manifest_description(inferencer, scan),
            adapter_type=key if scan.has_adapter else None,
            events_dir=events_dir,
            actions_dir=actions_dir,
        )
        log.log(f"  PR #{number} 生成 plugin.toml")
    elif fix_manifest_identity(plugin_dir, account, key):
        changed.append("plugin.toml")
        log.log(f"  PR #{number} 修正 plugin.toml 的 key / author")

    build = subprocess.run(
        [
            sys.executable,
            "-m",
            "system.plugin_market.loom_publish",
            "build",
            str(plugin_dir),
            "--apply",
            str(WORK),
            "--clean-source",
        ],
        cwd=str(PY_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if build.returncode != 0:
        raise RuntimeError(f"冻结失败：{(build.stderr or build.stdout).strip()}")
    log.log(build.stdout.strip())

    git("add", "-A")
    git("commit", "-m", f"发布 {account}/{key}（PR #{number}）")
    _push(config, branch)

    detail = "\n".join(f"- `{item}`" for item in changed) or "- （没有需要补全的内容）"
    return (
        "## 已合并并发布\n\n"
        f"作者：`{account}`　插件：`{key}`\n\n"
        f"机器人补全的文件：\n{detail}\n\n"
        "Loom 侧会在下一个同步周期（默认 30 秒）拉到这个新版本。"
    )


def _push(config: dict, branch: str) -> None:
    """推送用带 token 的地址——token 只在内存里拼一次，不写进 `.git/config`。"""
    token = str(SECRETS.get("gitee_token") or "").strip()
    if not token:
        raise RuntimeError(
            "缺少 Gitee token：写进本地密钥文件（默认 python/secrets/plugin-market.json 的 gitee_token）"
        )
    host, owner, repo = parse_repo_url(config["repo"]["url"])
    git("push", authenticated_url(token, host, owner, repo), branch)


def _ahead_of_remote(branch: str) -> bool:
    """本地是不是有还没推出去的提交。"""
    output = git("rev-list", "--count", f"origin/{branch}..HEAD").strip()
    try:
        return int(output) > 0
    except ValueError:
        return False


def _head_is_publish_commit() -> bool:
    """HEAD 是不是机器人自己那条发布提交。"""
    return git("log", "-1", "--pretty=%s").strip().startswith("发布 ")


def _display_name(inferencer, scan) -> str:
    if scan.nodes:
        return inferencer.infer(scan.nodes[0]).name or scan.key
    return scan.key


def _description(inferencer, scan) -> str:
    if scan.nodes:
        return inferencer.infer(scan.nodes[0]).description
    return ""


def _manifest_description(inferencer, scan) -> str:
    """plugin.toml 的 description 不能为空；推断不出内容时用节点清单兜底。"""
    text = _description(inferencer, scan).strip()
    if text:
        return text
    names = "、".join(node.func_name for node in scan.nodes)
    return f"包含 {len(scan.nodes)} 个节点：{names}"


def _adapter_dirs(plugin_dir: Path) -> tuple[str | None, str | None]:
    """只声明实际存在的目录——声明了却不存在，扫描会直接报错。"""
    events = "events" if (plugin_dir / "events").is_dir() else None
    actions = "actions" if (plugin_dir / "actions").is_dir() else None
    return events, actions


def _ai_client(config: dict, log: BotLogger):
    """按配置 + 本地密钥建 AI 客户端；没开或没 key 就返回 None（退回启发式）。"""
    ai = config.get("ai") or {}
    if not ai.get("enabled", True):
        return None
    api_key = str(SECRETS.get("ai_api_key") or "").strip()
    if not api_key:
        return None
    try:
        return AiClient(
            ai.get("base_url", ""),
            api_key,
            ai.get("model", ""),
            json_mode=bool(ai.get("json_mode", True)),
            timeout=float(ai.get("timeout", 120)),
        )
    except AiError as exc:
        log.log(f"AI 不可用：{exc}")
        return None


def _reviewer(config: dict, log: BotLogger):
    client = _ai_client(config, log)
    return AiReviewer(client) if client else None


def _inferencer(config: dict, log: BotLogger):
    client = _ai_client(config, log)
    return AiInferencer(client) if client else HeuristicInferencer()


# ---------------------------------------------------------------------------
# 本地干跑
# ---------------------------------------------------------------------------


def test_review(src: Path, use_ai: bool, config: dict | None) -> int:
    """不联网的完整预演：闸门 → 补全 → check，但不合并、不推送。"""
    work = PY_ROOT / "_work" / "test-review"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    target = work / "sources" / "tester" / src.name
    shutil.copytree(src, target)
    # 干跑也要有一个像样的库根，check 才能验证「目标版本目录未被占用」那一步
    (work / "index.json").write_text(
        json.dumps({"schemaVersion": 1, "plugins": []}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    scan = scan_plugin(target)
    spec = gate_spec(scan)
    print(f"规范分级：{spec.level or 'REJECT'}")
    for item in [*spec.reasons, *spec.notes]:
        print(f"  {item}")
    if not spec.passed:
        return 1

    safety = gate_audit(target)
    print(f"安全扫描：{'通过' if safety.passed else '不通过'}")
    for item in [*safety.reasons, *safety.notes]:
        print(f"  {item}")
    if not safety.passed:
        return 1

    log = BotLogger(work / "logs", 7)
    inferencer = _inferencer(config or {}, log) if use_ai else HeuristicInferencer()
    scan, changed = complete_plugin(target, inferencer)
    print(f"补全：{'、'.join(changed) if changed else '无需补全'}")
    if scan.problems:
        for item in scan.problems:
            print(f"  ✗ {item}")
        return 1

    if not (target / "plugin.toml").is_file():
        events_dir, actions_dir = _adapter_dirs(target)
        write_manifest(
            target,
            key=target.name,
            author="tester",
            version="0.1.0",
            name=_display_name(inferencer, scan),
            description=_manifest_description(inferencer, scan),
            adapter_type=target.name if scan.has_adapter else None,
            events_dir=events_dir,
            actions_dir=actions_dir,
        )
        print("补全：plugin.toml")
    elif fix_manifest_identity(target, "tester", target.name):
        print("补全：plugin.toml（修正 key / author）")

    check = subprocess.run(
        [
            sys.executable,
            "-m",
            "system.plugin_market.loom_publish",
            "check",
            str(target),
        ],
        cwd=str(PY_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    print(check.stdout.strip())
    if check.returncode != 0:
        print(check.stderr.strip(), file=sys.stderr)
        return 1
    print(f"\n产物在 {target}，可以直接看补全结果。")
    return 0


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------


def ensure_working_copy(
    config: dict, token: str, host: str, owner: str, repo: str, log: BotLogger
) -> Path:
    """保证机器人有一份自己的插件库工作副本（`<work_dir>/repo`）。

    不碰 Loom 自己那份（`python/plugins/<库>/repo`）：两边都在同一个 checkout 上
    pull / commit 会互相打架，而且 Loom 的同步器走 `pull --ff-only`，会被机器人
    还没推出去的提交顶住。

    克隆用带 token 的地址，克隆完立刻把 `origin` 改回干净地址——token 不留在 `.git/config`。
    """
    work_dir = PY_ROOT / str(config["work_dir"])
    work_dir.mkdir(parents=True, exist_ok=True)
    target = work_dir / "repo"
    branch = config["repo"]["branch"]
    clean_url = f"https://{host}/{owner}/{repo}.git"
    if not (target / ".git").is_dir():
        if target.exists():
            shutil.rmtree(target)
        result = subprocess.run(
            [
                "git",
                "clone",
                "--branch",
                branch,
                authenticated_url(token, host, owner, repo),
                str(target),
            ],
            cwd=str(work_dir),
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if result.returncode != 0:
            raise RuntimeError(f"克隆插件库失败：{(result.stderr or result.stdout).strip()}")
        git("remote", "set-url", "origin", clean_url, cwd=target)
        log.log(f"插件库已克隆到 {target}")
        return target

    git("fetch", "origin", branch, cwd=target)
    git("checkout", branch, cwd=target)
    try:
        git("pull", "--ff-only", "origin", branch, cwd=target)
    except RuntimeError as exc:
        # 上一次发布提交没推出去时会分叉。那不是错误——发布那一步会把它补推上去。
        log.log(f"工作副本没能快进（多半是上次发布没推出去），继续：{exc}")
    return target


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="Loom 插件库 PR 机器人")
    parser.add_argument(
        "--config",
        metavar="<json>",
        help=f"非密钥配置；不传则读环境变量 {market_config.ENV_SETTINGS}",
    )
    parser.add_argument(
        "--secrets",
        metavar="<json>",
        help=f"本地密钥文件；默认 {market_config.DEFAULT_SECRETS_FILE}",
    )
    parser.add_argument("--once", action="store_true", help="只跑一轮就退出")
    parser.add_argument("--test-review", metavar="<插件目录>", help="本地干跑，不联网")
    parser.add_argument("--no-ai", action="store_true", help="干跑时不调用 AI，用启发式兜底")
    args = parser.parse_args()

    global WORK
    secrets_file = Path(args.secrets) if args.secrets else None
    try:
        SECRETS.update(market_config.load_secrets(secrets_file))
        config = market_config.load_settings(Path(args.config) if args.config else None)
    except market_config.ConfigError as exc:
        print(f"✗ {exc}", file=sys.stderr)
        return 1

    if args.test_review:
        return test_review(Path(args.test_review), use_ai=not args.no_ai, config=config)

    if not config.get("enabled", False):
        print("插件库机器人未启用（sys_config 的 plugin.market.enabled），退出")
        return 0

    work_dir = PY_ROOT / str(config["work_dir"])
    log = BotLogger(work_dir, int(config["log_days"]))
    lock = PidLock(work_dir / "bot.pid")
    lock.acquire()

    try:
        host, owner, repo = parse_repo_url(str((config.get("repo") or {}).get("url") or ""))
    except GiteeError as exc:
        log.log(f"配置有误：{exc}")
        lock.release()
        return 1

    token = str(SECRETS.get("gitee_token") or "").strip()
    if not token:
        log.log(f"缺少 Gitee token：写进 {market_config.secrets_path(secrets_file)} 的 gitee_token")
        lock.release()
        return 1

    state = State(work_dir / "state.json")
    client = GiteeClient(token, owner, repo)
    log.log(
        f"机器人启动：仓库 {owner}/{repo}，轮询间隔 {config['poll_interval_seconds']}s，"
        f"日志保留 {config['log_days']} 天"
    )
    try:
        WORK = ensure_working_copy(config, token, host, owner, repo, log)
        while True:
            # 先收尾上次没做完的：合并成功但发布失败的 PR 已经不在「开放 PR」列表里了，
            # 只看开放列表的话它会永远卡在那里。
            for number, item in _resumable(state):
                try:
                    pull = client.get_pull(number)
                except GiteeError as exc:
                    log.log(f"PR #{number} 读不到，跳过续做：{exc}")
                    continue
                if str((pull.get("head") or {}).get("sha") or "") != item.get("sha"):
                    continue
                try:
                    process_pull(client, config, state, log, pull)
                except Exception as exc:  # noqa: BLE001
                    log.log(f"PR #{number} 续做发布出错：{exc}")
            try:
                for pull in client.list_open_pulls():
                    try:
                        process_pull(client, config, state, log, pull)
                    except Exception as exc:  # noqa: BLE001 - 单个 PR 出错不该带走整个机器人
                        log.log(f"PR #{pull.get('number')} 处理出错：{exc}")
            except GiteeError as exc:
                log.log(f"拉取 PR 列表失败：{exc}")
            if args.once:
                break
            time.sleep(float(config["poll_interval_seconds"]))
    except KeyboardInterrupt:
        log.log("收到中断，退出")
    finally:
        lock.release()
    return 0


if __name__ == "__main__":
    sys.exit(main())
