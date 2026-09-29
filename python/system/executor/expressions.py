"""分支条件表达式：受限白名单解析与求值。

只允许比较、四则运算、逻辑运算和 `len(X)`；返回值占位符必须是 `X`，
不允许属性访问、下标、容器字面量和其他函数调用。
"""

from __future__ import annotations

import ast
from typing import Any

from system.executor.errors import ParamError

_ALLOWED_NODES = (
    ast.Expression,
    ast.Compare,
    ast.BinOp,
    ast.BoolOp,
    ast.UnaryOp,
    ast.Constant,
    ast.Name,
    ast.Load,
    ast.Call,
    ast.Add,
    ast.Sub,
    ast.Mult,
    ast.Div,
    ast.FloorDiv,
    ast.Mod,
    ast.Eq,
    ast.NotEq,
    ast.Lt,
    ast.LtE,
    ast.Gt,
    ast.GtE,
    ast.And,
    ast.Or,
    ast.Not,
    ast.USub,
    ast.UAdd,
)


def validate(expression: str) -> None:
    """校验表达式语法与白名单，非法直接报错。"""
    text = (expression or "").strip()
    if not text:
        return
    try:
        tree = ast.parse(text, mode="eval")
    except SyntaxError as exc:
        raise ParamError(f"分支条件表达式语法错误：{exc}") from exc
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and node.id not in {"X", "len"}:
            raise ParamError(f"分支条件表达式只允许使用 X 与 len()，不允许引用 {node.id}")
        if isinstance(node, ast.Call):
            valid = (
                isinstance(node.func, ast.Name)
                and node.func.id == "len"
                and len(node.args) == 1
                and not node.keywords
            )
            if not valid:
                raise ParamError("分支条件表达式只允许调用 len(X)")
        if isinstance(node, ast.Attribute):
            raise ParamError("分支条件表达式不允许属性访问")
        if isinstance(node, (ast.Subscript, ast.List, ast.Tuple, ast.Dict, ast.Set)):
            raise ParamError("分支条件表达式不允许容器字面量或下标访问")
        if not isinstance(node, _ALLOWED_NODES):
            raise ParamError(f"分支条件表达式包含不允许的语法：{type(node).__name__}")


def evaluate(expression: str, result: Any) -> bool:
    """在受限命名空间内求值，返回布尔结果。"""
    text = (expression or "").strip()
    if not text:
        return bool(result)
    validate(text)
    code = compile(ast.parse(text, mode="eval"), "<branch>", "eval")
    try:
        return bool(eval(code, {"__builtins__": {}}, {"X": result, "len": len}))
    except Exception as exc:  # noqa: BLE001 - 求值异常统一转参数错误
        raise ParamError(f"分支条件表达式求值失败：{exc}") from exc
