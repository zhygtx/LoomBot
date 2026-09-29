"""工作流运行时的分类错误。"""

from __future__ import annotations


class WorkflowError(Exception):
    """带分类错误码的执行错误。"""

    code = "FAILED"

    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        if code:
            self.code = code


class ParamError(WorkflowError):
    code = "PARAM_INVALID"


class NodeError(WorkflowError):
    code = "NODE_FAILED"


class ActionError(WorkflowError):
    code = "ACTION_FAILED"


class DefinitionError(WorkflowError):
    code = "DEFINITION_MISSING"
