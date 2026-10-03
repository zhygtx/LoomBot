"""插件源码安全静态审核（AST 规则扫描，纯标准库）。

用法：
  python scripts/audit.py <源码目录> [--json report.json] [--strict]

规则覆盖：动态执行/导入、系统命令、文件删除、网络、反序列化、敏感信息、硬编码网络地址、违规关键词。

语义：输出的是**可疑候选清单**，最终判定交给 AI 审核 / 人工确认——静态规则本身不断言恶意。

- 硬编码网络地址（网络调用参数 / 变量赋值 / 函数默认参数里的 URL 字面量）→ HIGH：
  域名易主会被拿去做供应链劫持，平台地址必须由用户在连接配置里指定。
- 疑似违规关键词（翻墙 / 木马 / 赌博 / 诈骗 / 挖矿等）→ HIGH：通常没有合法用途，直接拦。
- 插件私有 `data/` 目录内的文件读写删除自动豁免（命中行 ±3 行窗口内出现 data 标记）。
- 某行确认安全时，在行尾加 `# no-audit` 显式豁免。

退出码：0 通过（可能含 INFO / 中危警告）；2 存在高危项（--strict 时中危也算不通过）。
"""

from __future__ import annotations

import argparse
import ast
import json
import re
import sys
from pathlib import Path

BANNED_PARTS = {"data", "__pycache__", "_deps", ".git", "venv", ".venv"}

# (调用名后缀, 模块前缀集合(空=任意), 严重级, 说明)
CALL_RULES: list[tuple[str, tuple[str, ...], str, str]] = [
    ("eval", (), "HIGH", "动态执行任意代码（eval）"),
    ("exec", (), "HIGH", "动态执行任意代码（exec）"),
    ("compile", (), "HIGH", "动态编译代码"),
    ("__import__", (), "HIGH", "动态导入模块"),
    ("import_module", ("importlib",), "HIGH", "动态导入模块（importlib）"),
    ("system", ("os",), "HIGH", "执行系统命令（os.system）"),
    ("popen", ("os",), "HIGH", "执行系统命令（os.popen）"),
    ("run", ("subprocess",), "HIGH", "执行系统命令（subprocess）"),
    ("call", ("subprocess",), "HIGH", "执行系统命令（subprocess）"),
    ("Popen", ("subprocess",), "HIGH", "执行系统命令（subprocess）"),
    ("check_output", ("subprocess",), "HIGH", "执行系统命令（subprocess）"),
    ("check_call", ("subprocess",), "HIGH", "执行系统命令（subprocess）"),
    ("remove", ("os",), "HIGH", "删除文件（os.remove）"),
    ("unlink", ("os",), "HIGH", "删除文件（os.unlink）"),
    ("rmdir", ("os",), "HIGH", "删除目录（os.rmdir）"),
    ("removedirs", ("os",), "HIGH", "递归删除目录（os.removedirs）"),
    ("rmtree", ("shutil",), "HIGH", "递归删除目录树（shutil.rmtree）"),
    ("socket", ("socket",), "MEDIUM", "原始 socket（常见合法用途：WebSocket/长连接，需确认目标）"),
    ("create_server", ("socket",), "MEDIUM", "开启网络监听（需确认用途）"),
    ("bind", ("socket",), "MEDIUM", "绑定网络端口（需确认用途）"),
    ("connect", ("socket",), "MEDIUM", "发起网络连接（常见合法用途：WebSocket/长连接，需确认目标）"),
    ("loads", ("pickle",), "MEDIUM", "反序列化 pickle（存在任意代码执行风险）"),
    ("load", ("pickle",), "MEDIUM", "反序列化 pickle（存在任意代码执行风险）"),
    ("loads", ("yaml",), "MEDIUM", "反序列化 yaml（务必使用安全 Loader）"),
    ("load", ("yaml",), "MEDIUM", "反序列化 yaml（务必使用安全 Loader）"),
    ("loads", ("marshal",), "MEDIUM", "反序列化 marshal"),
    ("get", ("requests", "httpx"), "MEDIUM", "HTTP 请求（常见合法用途：公开 API，需确认非数据外传）"),
    ("post", ("requests", "httpx"), "MEDIUM", "HTTP 请求（常见合法用途：公开 API，需确认非数据外传）"),
    ("put", ("requests", "httpx"), "MEDIUM", "HTTP 请求（常见合法用途：公开 API，需确认非数据外传）"),
    ("delete", ("requests", "httpx"), "MEDIUM", "HTTP 请求（常见合法用途：公开 API，需确认非数据外传）"),
    ("patch", ("requests", "httpx"), "MEDIUM", "HTTP 请求（常见合法用途：公开 API，需确认非数据外传）"),
    ("request", ("requests", "httpx"), "MEDIUM", "HTTP 请求（常见合法用途：公开 API，需确认非数据外传）"),
    ("urlopen", ("urllib.request", "urllib"), "MEDIUM", "HTTP 请求（常见合法用途：公开 API，需确认非数据外传）"),
    ("Request", ("urllib.request",), "MEDIUM", "构造 HTTP 请求（需确认目标）"),
    ("ClientSession", ("aiohttp",), "MEDIUM", "HTTP 会话（常见合法用途：公开 API，需确认非数据外传）"),
    ("write_text", (), "MEDIUM", "写入文件（pathlib，请确认写入路径）"),
    ("write_bytes", (), "MEDIUM", "写入文件（pathlib，请确认写入路径）"),
    ("unlink", (), "MEDIUM", "删除文件（pathlib，请确认路径）"),
    ("mkdir", (), "INFO", "创建目录（请确认路径）"),
    ("input", (), "INFO", "交互式输入（机器人场景通常无意义）"),
    ("b64encode", ("base64",), "INFO", "base64 编码（如用于混淆请说明用途）"),
    ("b64decode", ("base64",), "INFO", "base64 解码（如用于混淆请说明用途）"),
    ("getenv", ("os",), "INFO", "读取环境变量"),
    ("sleep", ("time",), "INFO", "阻塞等待"),
]

WRITE_MODES = {"w", "a", "x"}
URL_SCHEME_RE = re.compile(r"(?:https?|wss?)://")
NETWORK_CALL_TAILS = {
    "get",
    "post",
    "put",
    "delete",
    "patch",
    "request",
    "urlopen",
    "Request",
    "ClientSession",
}
COMPLIANCE_KEYWORDS = (
    "v2ray",
    "shadowsocks",
    "trojan",
    "翻墙",
    "木马",
    "远控",
    "肉鸡",
    "挖矿",
    "miner",
    "赌博",
    "博彩",
    "下注",
    "诈骗",
    "刷单",
    "假证",
    "毒品",
    "枪支",
    "黑客",
    "入侵",
    "勒索",
    "蠕虫",
)


def _dotted_name(node: ast.AST) -> str | None:
    """把 Call.func 转成点分全名；Name 或 Attribute 链，其余返回 None。"""
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        base = _dotted_name(node.value)
        if base is None:
            return None
        return f"{base}.{node.attr}"
    return None


def _match_rule(full: str, rule: tuple) -> bool:
    tail, prefixes, _sev, _desc = rule
    if not prefixes:
        return full == tail or full.endswith("." + tail)
    for prefix in prefixes:
        if full == prefix + "." + tail or full.startswith(prefix + "." + tail):
            return True
    return False


def _open_mode(node: ast.Call) -> str | None:
    """内置 open() 的 mode：第二位置参数或 mode= 关键字，取字符串字面量。"""
    args = node.args
    keywords = {kw.arg: kw.value for kw in node.keywords}
    if len(args) >= 2 and isinstance(args[1], ast.Constant) and isinstance(args[1].value, str):
        return args[1].value
    mode = keywords.get("mode")
    if isinstance(mode, ast.Constant) and isinstance(mode.value, str):
        return mode.value
    return None


def _line_no_audit(src_lines: list[str], line: int) -> bool:
    return "# no-audit" in (src_lines[line - 1] if 1 <= line <= len(src_lines) else "")


def check_call(node: ast.Call, src_lines: list[str], rel: str) -> list[dict]:
    findings: list[dict] = []
    full = _dotted_name(node.func)
    if not full:
        return findings

    for rule in CALL_RULES:
        if _match_rule(full, rule):
            if _line_no_audit(src_lines, node.lineno):
                return findings
            findings.append(
                {
                    "severity": rule[2],
                    "file": rel,
                    "line": node.lineno,
                    "desc": rule[3],
                    "code": src_lines[node.lineno - 1].strip()[:120],
                }
            )
            break

    if full == "open":
        mode = _open_mode(node)
        if mode and any(ch in WRITE_MODES for ch in mode):
            if not _line_no_audit(src_lines, node.lineno):
                findings.append(
                    {
                        "severity": "MEDIUM",
                        "file": rel,
                        "line": node.lineno,
                        "desc": f"以写模式 open(...)（mode={mode!r}，请确认写入路径）",
                        "code": src_lines[node.lineno - 1].strip()[:120],
                    }
                )
    return findings


def check_hardcoded_urls(tree: ast.AST, src_lines: list[str], rel: str) -> list[dict]:
    """硬编码网络地址 → HIGH。域名易主会被拿去做供应链劫持，目标必须由用户运行时指定。"""
    findings: list[dict] = []

    def is_url_literal(node: ast.AST | None) -> bool:
        return (
            isinstance(node, ast.Constant)
            and isinstance(node.value, str)
            and bool(URL_SCHEME_RE.search(node.value))
        )

    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            full = _dotted_name(node.func) or ""
            if full.rsplit(".", 1)[-1] in NETWORK_CALL_TAILS:
                candidates = list(node.args) + [kw.value for kw in node.keywords]
                if any(is_url_literal(arg) for arg in candidates) and not _line_no_audit(
                    src_lines, node.lineno
                ):
                    findings.append(
                        {
                            "severity": "HIGH",
                            "file": rel,
                            "line": node.lineno,
                            "desc": "硬编码网络地址（必须由用户/配置运行时指定，防域名易主风险）",
                            "code": src_lines[node.lineno - 1].strip()[:120],
                        }
                    )
        elif isinstance(node, (ast.Assign, ast.AnnAssign)):
            if is_url_literal(node.value) and not _line_no_audit(src_lines, node.lineno):
                findings.append(
                    {
                        "severity": "HIGH",
                        "file": rel,
                        "line": node.lineno,
                        "desc": "硬编码网络地址（变量赋值，必须由用户/配置运行时指定，防域名易主风险）",
                        "code": src_lines[node.lineno - 1].strip()[:120],
                    }
                )
        elif isinstance(node, ast.FunctionDef):
            if any(is_url_literal(default) for default in node.args.defaults) and not _line_no_audit(
                src_lines, node.lineno
            ):
                findings.append(
                    {
                        "severity": "HIGH",
                        "file": rel,
                        "line": node.lineno,
                        "desc": "硬编码网络地址（函数默认参数，必须由用户/配置运行时指定，防域名易主风险）",
                        "code": src_lines[node.lineno - 1].strip()[:120],
                    }
                )
    return findings


def check_compliance(src_lines: list[str], rel: str) -> list[dict]:
    """疑似违规关键词 → HIGH，需人工 / AI 确认。"""
    findings: list[dict] = []
    for index, line in enumerate(src_lines, 1):
        if "# no-audit" in line:
            continue
        for keyword in COMPLIANCE_KEYWORDS:
            if keyword in line:
                findings.append(
                    {
                        "severity": "HIGH",
                        "file": rel,
                        "line": index,
                        "desc": f"疑似违规关键词「{keyword}」（违反法律法规风险，需确认）",
                        "code": line.strip()[:120],
                    }
                )
                break
    return findings


def _apply_data_dir_exemption(findings: list[dict], line_cache: dict[str, list[str]]) -> list[dict]:
    """data/ 目录内的文件读写删除视为合法（插件运行时私有数据目录约定）。

    启发式：命中行 ±3 行窗口内出现 data 标记（含 DATA_DIR 之类变量名），且操作是写/删 → 降级 INFO。
    """
    markers = ("删除", "写入", "写模式", "remove", "unlink", "rmdir", "rmtree")
    for finding in findings:
        lines = line_cache.get(finding.get("file", ""), [])
        line = finding.get("line") or 1
        window = " ".join(lines[max(0, line - 4) : min(len(lines), line + 3)])
        if "data" in window and any(marker in finding["desc"] for marker in markers):
            finding["severity"] = "INFO"
            finding["desc"] += "（命中 data/ 数据目录，视为合法）"
    return findings


def scan_dir(src: Path) -> list[dict]:
    """扫描目录下所有 .py，返回 findings。"""
    findings: list[dict] = []
    line_cache: dict[str, list[str]] = {}
    for path in sorted(src.rglob("*.py")):
        if any(part in BANNED_PARTS for part in path.relative_to(src).parts):
            continue
        rel = path.relative_to(src).as_posix()
        try:
            src_lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
            tree = ast.parse("\n".join(src_lines))
        except SyntaxError as exc:
            findings.append(
                {
                    "severity": "HIGH",
                    "file": rel,
                    "line": exc.lineno or 0,
                    "desc": f"Python 语法错误：{exc.msg}",
                    "code": "",
                }
            )
            continue
        line_cache[rel] = src_lines
        for node in ast.walk(tree):
            if isinstance(node, ast.Call):
                findings.extend(check_call(node, src_lines, rel))
        findings.extend(check_hardcoded_urls(tree, src_lines, rel))
        findings.extend(check_compliance(src_lines, rel))
    return _apply_data_dir_exemption(findings, line_cache)


def render_report(findings: list[dict]) -> str:
    order = {"HIGH": 0, "MEDIUM": 1, "INFO": 2}
    findings.sort(key=lambda item: (order.get(item["severity"], 9), item["file"], item["line"]))
    lines = [f"共发现 {len(findings)} 项："]
    for finding in findings:
        tag = {"HIGH": "🔴", "MEDIUM": "🟡", "INFO": "⚪"}.get(finding["severity"], "?")
        lines.append(
            f"  {tag} [{finding['severity']}] {finding['file']}:{finding['line']}  {finding['desc']}"
        )
        if finding["code"]:
            lines.append(f"      {finding['code']}")
    return "\n".join(lines)


def verdict(findings: list[dict], strict: bool) -> tuple[str, int]:
    severities = {item["severity"] for item in findings}
    if "HIGH" in severities:
        return "REVIEW REQUIRED（存在高危项，需人工确认或打回修改）", 2
    if strict and "MEDIUM" in severities:
        return "PASS WITH WARNINGS（--strict 下中危视为不通过）", 2
    if "MEDIUM" in severities:
        return "PASS WITH WARNINGS（存在中危项，建议人工过目）", 0
    return "PASS（未发现风险项）", 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="插件源码安全静态审核（AST 规则扫描）")
    parser.add_argument("src", metavar="<源码目录>")
    parser.add_argument("--json", metavar="report.json", help="把完整报告写入 JSON 文件")
    parser.add_argument("--strict", action="store_true", help="中危项也视为不通过")
    args = parser.parse_args()

    src = Path(args.src)
    if not src.is_dir():
        print(f"源码目录不存在：{src}", file=sys.stderr)
        return 1
    findings = scan_dir(src)
    print(render_report(findings))
    message, code = verdict(findings, args.strict)
    print(f"\n== 审核结论：{message}（退出码 {code}）==")
    if args.json:
        Path(args.json).write_text(
            json.dumps(
                {"verdict": message, "exit_code": code, "findings": findings},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"报告已写入：{args.json}")
    return code


if __name__ == "__main__":
    sys.exit(main())
