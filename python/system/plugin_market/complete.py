#!/usr/bin/env python3
"""把「最低规范」补全成「最佳规范」。

**最低规范只有一条：函数放在对的目录里。**

- `nodes/` 下的顶层函数 → 工作流节点（NODE）
- `events/` 下的顶层函数 → 适配器事件（EVENT）
- `actions/` 下的顶层函数 → 适配器动作（ACTION）
- 下划线开头的函数、非顶层函数一律跳过（那是辅助代码）

补全只写「框架内容」：

- 缺失的 `@node` / `@event` / `@action` 装饰器
- 缺失的 `Annotated[T, Param(description=...)]` 包装（类型由推断器给，**拿不准写 `object`**）
- 缺失的返回值注解
- 缺失的 import
- 整个 `plugin.toml`

**绝不碰**：函数体、函数名、参数名 / 顺序 / 默认值、目录位置，以及作者已经写好的类型。
这条边界是硬的——改了就会改语义。

`object` 在 Loom 里等于「不做类型转换、原样传递」（见 `system/executor/convert.py`），
所以推断器拿不准时写 `object` 是安全的：最坏情况只是画布上少个类型化编辑器，不会因为猜错类型
把数据悄悄转坏。
"""

from __future__ import annotations

import argparse
import ast
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

DIRECTORY_KINDS = (("nodes", "NODE"), ("events", "EVENT"), ("actions", "ACTION"))
KIND_DECORATOR = {"NODE": "node", "EVENT": "event", "ACTION": "action"}
KIND_MODULE = {"NODE": "loom_node", "EVENT": "loom_adapter", "ACTION": "loom_adapter"}

# Loom 认的类型；推断器只能从这个集合里挑，其余一律 object
TYPE_WHITELIST = frozenset(
    {
        "str",
        "int",
        "float",
        "bool",
        "bytes",
        "list",
        "dict",
        "set",
        "tuple",
        "frozenset",
        "Decimal",
        "UUID",
        "Path",
        "datetime",
        "date",
        "time",
        "object",
    }
)
SAFE_TYPE = "object"
MANIFEST_NAME = "plugin.toml"


@dataclass
class ParamSpec:
    """函数签名里的一个参数。"""

    name: str
    annotation: str | None
    has_meta: bool
    default: str | None
    keyword_only: bool

    @property
    def nullable(self) -> bool:
        return self.default == "None"


@dataclass
class NodeInfo:
    """一个待补全（或已经写好）的节点函数。"""

    path: Path
    relative: str
    lineno: int
    end_lineno: int
    func_name: str
    kind: str
    key: str
    declared: bool
    declared_name: str | None
    declared_description: str | None
    params: list[ParamSpec]
    return_annotation: str | None
    is_async: bool
    has_var_args: bool
    body_start_line: int
    body_start_col: int
    docstring: str | None
    source: str

    @property
    def needs_ctx(self) -> bool:
        """工作流节点的第一个位置参数必须是 `ctx`；没写就由补全器插一个。"""
        if self.kind != "NODE":
            return False
        positional = [p for p in self.params if not p.keyword_only]
        return not positional or positional[0].name != "ctx"


@dataclass
class PluginScan:
    """宽松扫描的结果。不看装饰器，只看位置。"""

    root: Path
    key: str
    nodes: list[NodeInfo] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)

    @property
    def by_kind(self) -> dict[str, list[NodeInfo]]:
        result: dict[str, list[NodeInfo]] = {}
        for node in self.nodes:
            result.setdefault(node.kind, []).append(node)
        return result

    @property
    def has_adapter(self) -> bool:
        return any(node.kind in {"EVENT", "ACTION"} for node in self.nodes)


# ---------------------------------------------------------------------------
# 宽松扫描
# ---------------------------------------------------------------------------


def _literal_str(node: ast.AST | None) -> str | None:
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    return None


def _decorator_call(func: ast.FunctionDef | ast.AsyncFunctionDef, name: str) -> ast.Call | None:
    for decorator in func.decorator_list:
        if isinstance(decorator, ast.Call) and isinstance(decorator.func, ast.Name):
            if decorator.func.id == name:
                return decorator
    return None


def _keyword_text(call: ast.Call, name: str) -> str | None:
    for keyword in call.keywords:
        if keyword.arg == name:
            return _literal_str(keyword.value)
    return None


def _annotation_text(source_lines: list[str], node: ast.AST | None) -> str | None:
    if node is None:
        return None
    return ast.get_source_segment("\n".join(source_lines), node)


def _is_annotated_with_meta(annotation: ast.AST | None) -> bool:
    """是不是已经包了 `Annotated[..., Param(...)]`。"""
    if not isinstance(annotation, ast.Subscript):
        return False
    if not (isinstance(annotation.value, ast.Name) and annotation.value.id == "Annotated"):
        return False
    slice_node = annotation.slice
    metadata = slice_node.elts[1:] if isinstance(slice_node, ast.Tuple) else []
    for item in metadata:
        if isinstance(item, ast.Call) and isinstance(item.func, ast.Name):
            if item.func.id in {"Param", "Attribute"}:
                return True
    return False


def _param_specs(source_lines: list[str], func: ast.FunctionDef | ast.AsyncFunctionDef) -> list[ParamSpec]:
    args = func.args
    positional = list(args.posonlyargs) + list(args.args)
    defaults = list(args.defaults)
    default_start = len(positional) - len(defaults)
    specs: list[ParamSpec] = []

    def append(arg: ast.arg, default: ast.AST | None, keyword_only: bool) -> None:
        specs.append(
            ParamSpec(
                name=arg.arg,
                annotation=_annotation_text(source_lines, arg.annotation),
                has_meta=_is_annotated_with_meta(arg.annotation),
                default=_annotation_text(source_lines, default),
                keyword_only=keyword_only,
            )
        )

    for index, arg in enumerate(positional):
        append(arg, defaults[index - default_start] if index >= default_start else None, False)
    for offset, arg in enumerate(args.kwonlyargs):
        default = args.kw_defaults[offset] if offset < len(args.kw_defaults) else None
        append(arg, default, True)
    return specs


def scan_plugin(root: Path) -> PluginScan:
    """按目录位置识别节点，完全不看装饰器。"""
    root = root.resolve()
    scan = PluginScan(root=root, key=root.name)
    if not root.is_dir():
        scan.problems.append(f"插件目录不存在：{root}")
        return scan

    for dirname, kind in DIRECTORY_KINDS:
        directory = root / dirname
        if not directory.is_dir():
            continue
        for path in sorted(directory.rglob("*.py")):
            relative_parts = path.relative_to(directory).parts
            if any(part.startswith("_") or part == "__pycache__" for part in relative_parts):
                continue
            try:
                text = path.read_text(encoding="utf-8")
                tree = ast.parse(text, filename=str(path))
            except (OSError, SyntaxError) as exc:
                scan.problems.append(f"{path.relative_to(root).as_posix()}：无法解析（{exc}）")
                continue
            lines = text.splitlines()
            relative = path.relative_to(root).as_posix()
            found = 0
            for func in tree.body:
                if not isinstance(func, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    continue
                if func.name.startswith("_"):
                    continue
                found += 1
                decorator = _decorator_call(func, KIND_DECORATOR[kind])
                key = _literal_str(decorator.args[0]) if decorator and decorator.args else None
                scan.nodes.append(
                    NodeInfo(
                        path=path,
                        relative=relative,
                        lineno=func.lineno,
                        end_lineno=func.end_lineno or func.lineno,
                        func_name=func.name,
                        kind=kind,
                        key=key or func.name,
                        declared=decorator is not None,
                        declared_name=_keyword_text(decorator, "name") if decorator else None,
                        declared_description=_keyword_text(decorator, "description")
                        if decorator
                        else None,
                        params=_param_specs(lines, func),
                        return_annotation=_annotation_text(lines, func.returns),
                        is_async=isinstance(func, ast.AsyncFunctionDef),
                        has_var_args=func.args.vararg is not None or func.args.kwarg is not None,
                        body_start_line=func.body[0].lineno,
                        body_start_col=func.body[0].col_offset,
                        docstring=ast.get_docstring(func, clean=True),
                        source=text,
                    )
                )
            if found == 0:
                scan.problems.append(f"{relative}：目录里没有可用的顶层函数")

    if not scan.nodes:
        scan.problems.append(
            f"{root.name}：在 nodes/ events/ actions/ 下没找到任何函数——插件至少得有一个节点"
        )

    for node in scan.nodes:
        if node.kind in {"EVENT", "ACTION"}:
            if not node.is_async:
                scan.problems.append(
                    f"{node.relative}:{node.lineno} {node.func_name}：适配器的"
                    f"{'事件' if node.kind == 'EVENT' else '动作'}必须是 async def"
                )
            positional = [p for p in node.params if not p.keyword_only]
            if len(positional) != 2 or len(node.params) != 2:
                scan.problems.append(
                    f"{node.relative}:{node.lineno} {node.func_name}：适配器节点必须正好接受两个位置参数"
                    f"（事件 (conn, frame)，动作 (conn, params)）"
                )
    return scan


# ---------------------------------------------------------------------------
# 推断（启发式兜底；AI 版本见 ai.py）
# ---------------------------------------------------------------------------


@dataclass
class NodeInference:
    """推断器对一个节点的补全建议。"""

    name: str = ""
    description: str = ""
    param_types: dict[str, str] = field(default_factory=dict)
    param_descriptions: dict[str, str] = field(default_factory=dict)
    param_nullable: dict[str, bool] = field(default_factory=dict)
    return_type: str = SAFE_TYPE
    params_schema: dict | None = None
    payload_schema: dict | None = None


def safe_type(value: str | None) -> str:
    """把推断出来的类型夹到 Loom 认的集合里；不认识的降到 object。"""
    text = (value or "").strip()
    return text if text in TYPE_WHITELIST else SAFE_TYPE


class HeuristicInferencer:
    """不用 AI 的兜底推断：只用参数名、默认值和文档字符串。"""

    def infer(self, node: NodeInfo) -> NodeInference:
        description = (node.docstring or "").strip().splitlines()
        inference = NodeInference(
            name=node.func_name,
            description=description[0] if description else "",
        )
        for param in node.params:
            if param.name in {"ctx", "conn", "frame", "params"}:
                continue
            inference.param_types[param.name] = _type_from_default(param.default)
            inference.param_descriptions[param.name] = ""
            inference.param_nullable[param.name] = param.nullable
        return inference


def _type_from_default(default: str | None) -> str:
    """从默认值字面量猜类型；猜不出来就是 object。"""
    if default is None:
        return SAFE_TYPE
    text = default.strip()
    if text in {"True", "False"}:
        return "bool"
    if text == "None":
        return SAFE_TYPE
    if text.startswith(("'", '"', "f'", 'f"')):
        return "str"
    if text.startswith("["):
        return "list"
    if text.startswith("{"):
        return "dict"
    try:
        int(text)
        return "int"
    except ValueError:
        pass
    try:
        float(text)
        return "float"
    except ValueError:
        pass
    return SAFE_TYPE


# ---------------------------------------------------------------------------
# 源码改写
# ---------------------------------------------------------------------------


@dataclass
class Edit:
    """一处文本替换；起止都是 1-based 行 + 0-based 列。"""

    start_line: int
    start_col: int
    end_line: int
    end_col: int
    text: str


def _line_offsets(text: str) -> list[int]:
    offsets = [0]
    for index, char in enumerate(text):
        if char == "\n":
            offsets.append(index + 1)
    return offsets


def _offset(offsets: list[int], line: int, col: int) -> int:
    index = min(max(line - 1, 0), len(offsets) - 1)
    return offsets[index] + col


def _position(offsets: list[int], index: int) -> tuple[int, int]:
    """扁平下标换回 (1-based 行, 0-based 列)。"""
    for line_no in range(len(offsets) - 1, -1, -1):
        if offsets[line_no] <= index:
            return line_no + 1, index - offsets[line_no]
    return 1, 0


def apply_edits(text: str, edits: list[Edit]) -> str:
    """从后往前应用，避免前面的改动把后面的位置顶偏。"""
    offsets = _line_offsets(text)
    ordered = sorted(
        edits,
        key=lambda edit: (
            _offset(offsets, edit.start_line, edit.start_col),
            _offset(offsets, edit.end_line, edit.end_col),
        ),
        reverse=True,
    )
    result = text
    for edit in ordered:
        start = _offset(offsets, edit.start_line, edit.start_col)
        end = _offset(offsets, edit.end_line, edit.end_col)
        result = result[:start] + edit.text + result[end:]
    return result


def _import_offset(lines: list[str], tree: ast.Module) -> tuple[int, int]:
    """import 该插到哪：文档字符串和已有 import 之后。返回 (行, 列)。"""
    insert_line = 0
    body = tree.body
    if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant):
        if isinstance(body[0].value.value, str):
            insert_line = body[0].end_lineno or 1
    for stmt in body:
        if isinstance(stmt, (ast.Import, ast.ImportFrom)):
            insert_line = max(insert_line, stmt.end_lineno or 1)
    return insert_line + 1, 0


def _imported_names(tree: ast.Module) -> set[str]:
    names: set[str] = set()
    for stmt in ast.walk(tree):
        if isinstance(stmt, ast.Import):
            for alias in stmt.names:
                names.add(alias.asname or alias.name.split(".")[0])
        elif isinstance(stmt, ast.ImportFrom):
            for alias in stmt.names:
                names.add(alias.asname or alias.name)
    return names


def _signature_colon(func: ast.AST, lines: list[str]) -> tuple[int, int] | None:
    """找函数签名结尾那个冒号。

    从函数体第一句往前扫，第一个非空白字符就是它——不用自己去解析括号和默认值，
    签名里带 `{"a": 1}` 这种默认值也不会被误导。中间夹的注释行整行跳过。
    """
    body = getattr(func, "body", None)
    if not body:
        return None
    text = "\n".join(lines)
    offsets = _line_offsets(text)
    index = _offset(offsets, body[0].lineno, body[0].col_offset) - 1
    while index >= 0:
        char = text[index]
        if char in " \t\r\n":
            index -= 1
            continue
        if char == ":":
            break
        line_start = text.rfind("\n", 0, index) + 1
        if text[line_start : index + 1].lstrip().startswith("#"):
            index = line_start - 1
            continue
        return None
    if index < 0:
        return None
    return _position(offsets, index)


def _params_span(func: ast.AST, text: str, offsets: list[int]) -> tuple[int, int] | None:
    """参数列表那对括号的扁平下标区间（左括号, 右括号）。

    没有参数的函数要补 `ctx` 时得插在括号**里面**；插在冒号那儿会跑到返回值注解后面，
    生成 `-> boolctx` 这种语法合法但完全不对的东西。
    """
    start = _offset(offsets, getattr(func, "lineno", 1), getattr(func, "col_offset", 0))
    open_index = text.find("(", start)
    if open_index < 0:
        return None
    depth = 0
    for index in range(open_index, len(text)):
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return open_index, index
    return None


def _wrapper_args(param: ParamSpec, inference: NodeInference) -> list[str]:
    """`Param(...)` 里要写什么；返回空列表表示没必要包 `Annotated`。"""
    args: list[str] = []
    description = inference.param_descriptions.get(param.name) or ""
    if description:
        args.append(f"description={json.dumps(description, ensure_ascii=False)}")
    if inference.param_nullable.get(param.name, param.nullable):
        args.append("nullable=True")
    return args


def _render_annotation(param: ParamSpec, inference: NodeInference) -> str:
    """把参数重写成 `name: T` 或 `name: Annotated[T, Param(...)]`。

    推断器没话可说（没描述、不可空）时不包 `Annotated`——空 `Param()` 只是噪音。
    """
    declared = (param.annotation or "").strip()
    if param.has_meta:
        return f"{param.name}: {declared}"
    # 作者写了类型就沿用，绝不改；没写才用推断结果（拿不准是 object）
    inner = declared or safe_type(inference.param_types.get(param.name))
    args = _wrapper_args(param, inference)
    if not args:
        return f"{param.name}: {inner}"
    return f"{param.name}: Annotated[{inner}, Param({', '.join(args)})]"


def _decorator_text(node: NodeInfo, inference: NodeInference, key: str) -> str:
    parts = [json.dumps(key, ensure_ascii=False)]
    name = inference.name or node.declared_name or node.func_name
    parts.append(f"name={json.dumps(name, ensure_ascii=False)}")
    description = inference.description or node.declared_description or ""
    if description:
        parts.append(f"description={json.dumps(description, ensure_ascii=False)}")
    if node.kind == "ACTION" and inference.params_schema:
        parts.append(f"params_schema={json.dumps(inference.params_schema, ensure_ascii=False)}")
    if node.kind == "EVENT" and inference.payload_schema:
        parts.append(f"payload_schema={json.dumps(inference.payload_schema, ensure_ascii=False)}")
    decorator = KIND_DECORATOR[node.kind]
    single = f"@{decorator}({', '.join(parts)})"
    if len(single) <= 100:
        return single
    # 带 schema 的装饰器会很长，拆成多行才看得下去
    body = ",\n".join(f"    {part}" for part in parts)
    return f"@{decorator}(\n{body},\n)"


def build_edits(scan: PluginScan, inferences: dict[str, NodeInference]) -> dict[Path, list[Edit]]:
    """按文件汇总需要做的改写。"""
    per_file: dict[Path, list[Edit]] = {}
    parsed: dict[Path, tuple[str, list[str], ast.Module]] = {}
    needed_imports: dict[Path, set[str]] = {}

    for node in scan.nodes:
        cached = parsed.get(node.path)
        if cached is None:
            cached = (node.source, node.source.splitlines(), ast.parse(node.source))
            parsed[node.path] = cached
        source, lines, tree = cached
        offsets = _line_offsets(source)
        inference = inferences[node.func_name]
        edits = per_file.setdefault(node.path, [])
        needed = needed_imports.setdefault(node.path, set())
        func = _find_func(tree, node)

        # 1) 装饰器：插在 def 正上方（最内层），这样它拿到的是原始函数
        if not node.declared:
            edits.append(
                Edit(node.lineno, 0, node.lineno, 0, _decorator_text(node, inference, node.key) + "\n")
            )
            needed.add(KIND_DECORATOR[node.kind])

        # 2) 工作流节点缺 ctx：在最前面补一个，函数体本来就没用到它，插进去是安全的
        if node.needs_ctx and func is not None:
            first = _first_param(func)
            if first is not None:
                edits.append(
                    Edit(
                        first.lineno,
                        first.col_offset,
                        first.lineno,
                        first.col_offset,
                        "ctx, ",
                    )
                )
            else:
                # 没有参数：ctx 必须插在括号里面，不能插在签名冒号那儿
                span = _params_span(func, source, offsets)
                if span is not None:
                    line, col = _position(offsets, span[0] + 1)
                    edits.append(Edit(line, col, line, col, "ctx"))

        # 3) 参数注解：只替换参数本身那段文本
        for param, arg in _params_with_nodes(node.params, func):
            if param.name in {"ctx", "conn", "frame", "params"}:
                continue
            if param.has_meta:
                continue
            end_line = arg.end_lineno or arg.lineno
            end_col = arg.end_col_offset or arg.col_offset
            end = _offset(offsets, end_line, end_col)
            replacement = _render_annotation(param, inference)
            # 原来写成 `times=1`（没有注解）：顺手把等号一起吃掉，输出 `times: int = 1`
            if source[end : end + 1] == "=":
                replacement += " = "
                end_line, end_col = _position(offsets, end + 1)
            edits.append(Edit(arg.lineno, arg.col_offset, end_line, end_col, replacement))
            if _wrapper_args(param, inference):
                needed.add("Annotated")
                needed.add("Param")

        # 4) 返回值注解：插在签名冒号前面
        if not node.return_annotation and func is not None:
            colon = _signature_colon(func, lines)
            if colon is not None:
                edits.append(
                    Edit(
                        colon[0],
                        colon[1],
                        colon[0],
                        colon[1],
                        f" -> {safe_type(inference.return_type)}",
                    )
                )

    # 5) import：每个文件只加一次，缺什么加什么
    for path, names in needed_imports.items():
        if not names:
            continue
        _source, lines, tree = parsed[path]
        imported = _imported_names(tree)
        wanted: list[str] = []
        if "Annotated" in names and "Annotated" not in imported:
            wanted.append("from typing import Annotated")
        node_names = sorted(n for n in names if n in {"Param", "node"} and n not in imported)
        adapter_names = sorted(n for n in names if n in {"event", "action"} and n not in imported)
        if node_names:
            wanted.append(f"from loom_node import {', '.join(node_names)}")
        if adapter_names:
            wanted.append(f"from loom_adapter import {', '.join(adapter_names)}")
        if wanted:
            line, col = _import_offset(lines, tree)
            # 插入点后面本来就有空行的话就别再加一行，免得堆出三个空行
            next_index = line - 1
            already_blank = next_index < len(lines) and not lines[next_index].strip()
            suffix = "\n" if already_blank else "\n\n"
            per_file[path].append(Edit(line, col, line, col, "\n".join(wanted) + suffix))

    return per_file


def _find_func(tree: ast.Module, node: NodeInfo) -> ast.FunctionDef | ast.AsyncFunctionDef | None:
    for func in tree.body:
        if isinstance(func, (ast.FunctionDef, ast.AsyncFunctionDef)) and func.name == node.func_name:
            return func
    return None


def _first_param(func: ast.FunctionDef | ast.AsyncFunctionDef) -> ast.arg | None:
    args = list(func.args.posonlyargs) + list(func.args.args)
    return args[0] if args else None


def _params_with_nodes(
    params: list[ParamSpec], func: ast.FunctionDef | ast.AsyncFunctionDef | None
) -> list[tuple[ParamSpec, ast.arg]]:
    """把已解析的参数说明和 AST 里的参数节点按顺序对上，好拿到精确的文本区间。"""
    if func is None:
        return []
    args = list(func.args.posonlyargs) + list(func.args.args) + list(func.args.kwonlyargs)
    return list(zip(params, args))


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------


def complete_plugin(root: Path, inferencer) -> tuple[PluginScan, list[str]]:
    """扫描 + 补全，返回 (扫描结果, 改动过的文件列表)。"""
    scan = scan_plugin(root)
    if scan.problems:
        return scan, []
    inferences = {node.func_name: inferencer.infer(node) for node in scan.nodes}
    per_file = build_edits(scan, inferences)
    changed: list[str] = []
    for path, edits in per_file.items():
        original = path.read_text(encoding="utf-8")
        updated = apply_edits(original, edits)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed.append(path.relative_to(scan.root).as_posix())
    return scan, sorted(changed)


def write_manifest(
    root: Path,
    *,
    key: str,
    author: str,
    version: str,
    name: str,
    description: str,
    adapter_type: str | None = None,
    connection_schema: str | None = None,
    entry: str = "main.py",
    events_dir: str | None = None,
    actions_dir: str | None = None,
) -> Path:
    """生成 plugin.toml。适配器那部分只在真的有 events/ actions/ 时才写。

    目录声明必须和实际存在的目录一致——声明了却不存在，扫描会直接报错。
    """
    lines = [
        "[plugin]",
        f"key = {json.dumps(key, ensure_ascii=False)}",
        f"name = {json.dumps(name or key, ensure_ascii=False)}",
        f"version = {json.dumps(version, ensure_ascii=False)}",
        f"description = {json.dumps(description or '', ensure_ascii=False)}",
        f"author = {json.dumps(author, ensure_ascii=False)}",
    ]
    if adapter_type:
        lines += [
            "",
            "[[adapters]]",
            f"type = {json.dumps(adapter_type, ensure_ascii=False)}",
            f"entry = {json.dumps(entry, ensure_ascii=False)}",
            f"connection_schema = {json.dumps(connection_schema or 'connection-schema.json', ensure_ascii=False)}",
            'protocol_version = "1"',
            'schema_version = "1"',
        ]
        if events_dir:
            lines.append(f"events_dir = {json.dumps(events_dir, ensure_ascii=False)}")
        if actions_dir:
            lines.append(f"actions_dir = {json.dumps(actions_dir, ensure_ascii=False)}")
    path = root / MANIFEST_NAME
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="宽松扫描 + 规范补全（AI 版本见 ai.py）")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "apply"):
        item = sub.add_parser(name)
        item.add_argument("src", metavar="<插件目录>")
    args = parser.parse_args()

    root = Path(args.src)
    if args.command == "plan":
        scan = scan_plugin(root)
        print(f"插件键：{scan.key}")
        for node in scan.nodes:
            flags = []
            if not node.declared:
                flags.append("缺装饰器")
            if node.needs_ctx:
                flags.append("缺 ctx")
            usable = [p for p in node.params if p.name not in {"ctx", "conn", "frame", "params"}]
            if any(p.annotation is None for p in usable):
                flags.append("参数缺类型")
            elif any(not p.has_meta for p in usable):
                flags.append("参数缺说明")
            if not node.return_annotation:
                flags.append("缺返回值注解")
            print(f"  [{node.kind}] {node.relative}:{node.lineno} {node.func_name} → {node.key}"
                  f"  {'、'.join(flags) if flags else '已是最佳规范'}")
        for problem in scan.problems:
            print(f"  ✗ {problem}")
        return 1 if scan.problems else 0

    scan, changed = complete_plugin(root, HeuristicInferencer())
    for problem in scan.problems:
        print(f"✗ {problem}", file=sys.stderr)
    if scan.problems:
        return 1
    for item in changed:
        print(f"  ✓ 已补全 {item}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
