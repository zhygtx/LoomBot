"""Gitee API 的最小封装（纯标准库）。

只用到 PR 流程需要的那几个接口：列 PR、读 PR、读改动文件、评论、合并。
仓库的写操作（fetch / merge / push）走本地 git，见 auto_pr_bot.py。
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

API_BASE = "https://gitee.com/api/v5"


class GiteeError(Exception):
    """调用 Gitee 失败。"""


def parse_repo_url(url: str) -> tuple[str, str, str]:
    """从 `https://gitee.com/<owner>/<repo>.git` 里取出 (host, owner, repo)。

    仓库坐标只配一个 URL，省得 owner / repo 两处填得不一致。
    """
    text = (url or "").strip().rstrip("/")
    if text.endswith(".git"):
        text = text[:-4]
    if "://" in text:
        text = text.split("://", 1)[1]
    parts = [piece for piece in text.split("/") if piece]
    if len(parts) < 3:
        raise GiteeError(f"仓库地址看不出来 owner / repo：{url!r}（形如 https://gitee.com/用户/仓库.git）")
    return parts[0], parts[-2], parts[-1]


def authenticated_url(token: str, host: str, owner: str, repo: str) -> str:
    """带 token 的推送地址。

    token 只从本地密钥文件来，拼出来的地址只在内存里用一次，不写进 `.git/config`。
    """
    return f"https://{owner}:{token}@{host}/{owner}/{repo}.git"


class GiteeClient:
    def __init__(self, token: str, owner: str, repo: str, *, timeout: float = 60.0) -> None:
        if not token:
            raise GiteeError("缺少 gitee.token")
        self.token = token
        self.owner = owner
        self.repo = repo
        self.timeout = timeout

    # -- 基础请求 ---------------------------------------------------------

    def _request(self, method: str, path: str, body: dict | None = None, query: dict | None = None):
        url = f"{API_BASE}/repos/{self.owner}/{self.repo}{path}"
        if query:
            url += "?" + urllib.parse.urlencode(query)
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        request = urllib.request.Request(
            url,
            data=data,
            headers={
                "Content-Type": "application/json;charset=UTF-8",
                "Authorization": f"token {self.token}",
            },
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:400]
            raise GiteeError(f"{method} {path} 返回 {exc.code}：{detail}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise GiteeError(f"{method} {path} 失败：{exc}") from exc
        if not raw.strip():
            return None
        try:
            return json.loads(raw)
        except ValueError as exc:
            raise GiteeError(f"{method} {path} 返回的不是 JSON：{raw[:200]}") from exc

    # -- PR ---------------------------------------------------------------

    def list_open_pulls(self) -> list[dict]:
        data = self._request("GET", "/pulls", query={"state": "open", "per_page": 100})
        return data if isinstance(data, list) else []

    def get_pull(self, number: int) -> dict:
        data = self._request("GET", f"/pulls/{number}")
        if not isinstance(data, dict):
            raise GiteeError(f"PR #{number} 返回结构异常")
        return data

    def list_pull_files(self, number: int) -> list[dict]:
        data = self._request("GET", f"/pulls/{number}/files")
        return data if isinstance(data, list) else []

    def create_pull(self, *, title: str, head: str, base: str, body: str = "") -> dict:
        """开一个 PR。`head` 同仓库就是分支名，跨仓库是 `用户:分支`。"""
        data = self._request(
            "POST",
            "/pulls",
            body={"title": title, "head": head, "base": base, "body": body},
        )
        if not isinstance(data, dict):
            raise GiteeError("创建 PR 返回结构异常")
        return data

    def close_pull(self, number: int) -> None:
        """关掉一个 PR（不合并）。"""
        self._request("PATCH", f"/pulls/{number}", body={"state": "closed"})

    def comment(self, number: int, body: str) -> None:
        self._request("POST", f"/pulls/{number}/comments", body={"body": body})

    def approve(self, number: int, comment: str = "") -> None:
        """把 PR 标成审查通过。

        有些仓库开了「审查通过才能合并」，机器人自己就是审核方，所以合并前先走这一步。
        `force` 是 Gitee 这个接口的必填语义（强制给出审查结论）。
        """
        body: dict = {"force": True}
        if comment:
            body["comment"] = comment
        self._request("POST", f"/pulls/{number}/review", body=body)

    def merge(self, number: int, *, method: str = "squash", title: str = "") -> None:
        body = {"merge_method": method}
        if title:
            body["title"] = title
        self._request("PUT", f"/pulls/{number}/merge", body=body)
