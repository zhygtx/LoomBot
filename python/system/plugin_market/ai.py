"""OpenAI 兼容接口的调用封装（纯标准库，零第三方依赖）。

两件事：**推断补全内容** 和 **审核代码**。两者都要求模型输出 JSON。

审核的基调按「常规简单场景」来：只在常规用法下就会崩、会坏数据、有安全问题时才拦，
边界情况、极端输入、代码风格、缺少优化一律放行（写成 WARN 提一句就够）。
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request

from .complete import SAFE_TYPE, TYPE_WHITELIST, NodeInference, NodeInfo, safe_type

# 用 __TYPES__ 占位而不是 str.format：提示词里全是 JSON 示例，`{}` 到处都是，
# 用 format 就得把每个花括号都转义一遍，改一次踩一次。
_INFER_SYSTEM_TEMPLATE = """你在帮 Loom 插件库补全节点函数的「框架内容」。只输出 JSON，不要解释。

Loom 的约定：
- 工作流节点（NODE）必须是普通函数，第一个位置参数是 ctx，其余参数成为节点参数。
- 适配器事件（EVENT）签名是 async def handler(conn, frame)，动作（ACTION）是 async def handler(conn, params)。
- 参数说明走 Annotated[T, Param(description="...")]；返回值类型决定画布能引用哪些字段。

补全规则：
1. name 是中文展示名（4~10 字），description 是一句话说明。
2. type 只能从这个集合里选：__TYPES__
3. **类型要尽量给准**：参数名和函数体通常有明显线索——`*_id` / `count` / `times` / `size` 一般是 int，
   文本内容是 str，开关是 bool，列表是 list，键值结构是 dict。有线索就按线索给。
   **参数有默认值时，默认值就是最可靠的线索**：`=1` → int，`=1.0` → float，`=""` → str，
   `=True` → bool，`=[]` → list，空字典 → dict。除非函数体明显接受更宽的类型，否则按默认值给。
   只有**真的看不出**时才用 object——object 表示「不做类型转换、原样传递」，安全但等于没有类型提示，
   画布上会少一个类型化编辑器。宁可多推断一步，也不要一律写 object。
4. **nullable 只在签名里明确写了 `= None` 时才写 true，其余一律 false。**
   注意 Loom **不会**拿函数签名里的 Python 默认值兜底：`limit=100` 这种参数用户仍然必须填，
   标成 nullable 会让用户能留空、运行时真的传 None 进去，反而把它弄崩。
5. `connection_id` 固定是 int——Loom 的 `ctx.call_action` 收的就是 int。
6. 不要给 ctx / conn / frame / params 这几个框架参数生成说明。
7. 只根据函数体推断，不要发明函数里没有的东西。
8. **ACTION（动作）**：从函数体里看它读了 `params` 的哪些键（`params.get("x")` / `params["x"]`），
   生成 `params_schema`：
   {"type": "object", "required": ["x"], "properties": {"x": {"type": "integer", "title": "中文名", "description": "说明"}}}
   **带默认值的键不要放进 `required`**（`params.get("x", 默认)` 说明它可省）。
   一个键都看不出来就省略这个字段。
9. **EVENT（事件）**：同理看它读了 `frame` 的哪些键，生成 `payload_schema`（形状同 8）。看不出来就省略。

输出格式：
{"name": "...", "description": "...",
  "params": [{"name": "...", "type": "...", "description": "...", "nullable": false}],
  "return_type": "...",
  "params_schema": {},    // 只有 ACTION 才给
  "payload_schema": {}}   // 只有 EVENT 才给
"""

INFER_SYSTEM = _INFER_SYSTEM_TEMPLATE.replace(
    "__TYPES__", ",".join(sorted(TYPE_WHITELIST))
)

REVIEW_SYSTEM = """你是 Loom 插件库的代码审核员。只输出 JSON，不要解释。

**按「常规简单场景」评估**：插件只要在正常用法下能工作就算通过。
不要因为边界情况、极端输入、代码风格、缺少优化、缺少注释而打回——那些写进 suggestions 就够了。

只有下面几种情况才给 BLOCK：
- 常规用法下就会崩溃或必然抛异常
- 会破坏或丢失用户数据
- 有安全问题：把用户数据外传、执行任意代码、硬编码可疑网络地址、明显违规用途

verdict 取值：
- "OK"    常规用法下没问题
- "WARN"  只在非常规场景下才会出问题，或者只是可以更好——**放行**
- "BLOCK" 上面那三类问题

输出格式：{"verdict": "OK|WARN|BLOCK", "reasons": ["..."], "suggestions": ["..."]}
"""


class AiError(Exception):
    """调用模型失败。"""


class AiClient:
    """OpenAI 兼容的 chat/completions 客户端。"""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        *,
        json_mode: bool = True,
        timeout: float = 120.0,
    ) -> None:
        if not api_key:
            raise AiError("缺少 ai.api_key")
        self.endpoint = _endpoint_of(base_url)
        self.api_key = api_key
        self.model = model
        self.json_mode = json_mode
        self.timeout = timeout

    def chat(self, system: str, user: str) -> str:
        body: dict = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0,
        }
        if self.json_mode:
            body["response_format"] = {"type": "json_object"}
        request = urllib.request.Request(
            self.endpoint,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:400]
            raise AiError(f"模型接口返回 {exc.code}：{detail}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise AiError(f"调用模型失败：{exc}") from exc
        try:
            return payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise AiError(f"模型返回结构异常：{str(payload)[:300]}") from exc

    def chat_json(self, system: str, user: str) -> dict:
        text = self.chat(system, user).strip()
        # 有些服务会把 JSON 包在 ```json 里，这里兜一下
        if text.startswith("```"):
            text = text.strip("`")
            if text.lower().startswith("json"):
                text = text[4:]
        try:
            data = json.loads(text)
        except ValueError as exc:
            raise AiError(f"模型没有返回合法 JSON：{text[:300]}") from exc
        if not isinstance(data, dict):
            raise AiError("模型返回的不是 JSON 对象")
        return data


def _endpoint_of(base_url: str) -> str:
    """支持三种写法：服务根地址 / 带前缀的地址 / 完整接口地址。"""
    base = (base_url or "").strip().rstrip("/")
    if not base:
        raise AiError("缺少 ai.base_url")
    if base.endswith("/chat/completions"):
        return base
    return base + "/chat/completions"


class AiInferencer:
    """用模型推断节点的展示名、描述和参数说明。"""

    def __init__(self, client: AiClient) -> None:
        self.client = client

    def infer(self, node: NodeInfo) -> NodeInference:
        lines = node.source.splitlines()
        body = "\n".join(lines[node.lineno - 1 : node.end_lineno])
        user = (
            f"节点类型：{node.kind}\n"
            f"函数名：{node.func_name}\n"
            f"目录位置：{node.relative}\n\n"
            f"函数源码：\n```python\n{body}\n```\n"
        )
        data = self.client.chat_json(INFER_SYSTEM, user)
        return to_inference(node, data)


def to_inference(node: NodeInfo, data: dict) -> NodeInference:
    """把模型返回的 JSON 夹成安全的值——类型不在白名单里一律降级 object。"""
    inference = NodeInference(
        name=str(data.get("name") or "").strip() or node.func_name,
        description=str(data.get("description") or "").strip(),
        return_type=safe_type(str(data.get("return_type") or SAFE_TYPE)),
    )
    by_name = {param.name: param for param in node.params}
    for item in data.get("params") or []:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        if not name:
            continue
        declared = safe_type(str(item.get("type") or SAFE_TYPE))
        # 框架约定：connection_id 一定是 int（ctx.call_action 收的就是 int）
        if name == "connection_id":
            declared = "int"
        inference.param_types[name] = declared
        inference.param_descriptions[name] = str(item.get("description") or "").strip()
        # 安全网：只有签名里 `= None` 的参数才允许留空。模型偶尔看走眼，
        # 而 nullable 标错的后果是「用户留空 → 运行时真传 None → 崩」，代价太大。
        nullable = bool(item.get("nullable"))
        param = by_name.get(name)
        inference.param_nullable[name] = bool(nullable and param is not None and param.nullable)
    for field in ("params_schema", "payload_schema"):
        value = data.get(field)
        if isinstance(value, dict) and value.get("properties"):
            setattr(inference, field, value)
    return inference


class AiReviewer:
    """代码审核。只拦「常规用法下就会出问题」的那三类。"""

    def __init__(self, client: AiClient) -> None:
        self.client = client

    def review(self, files: dict[str, str]) -> dict:
        chunks = []
        for name, text in sorted(files.items()):
            chunks.append(f"### {name}\n```python\n{text}\n```")
        user = "以下是这个插件 PR 的全部源码：\n\n" + "\n\n".join(chunks)
        data = self.client.chat_json(REVIEW_SYSTEM, user)
        verdict = str(data.get("verdict") or "WARN").strip().upper()
        if verdict not in {"OK", "WARN", "BLOCK"}:
            verdict = "WARN"
        return {
            "verdict": verdict,
            "reasons": [str(item) for item in data.get("reasons") or []],
            "suggestions": [str(item) for item in data.get("suggestions") or []],
        }
