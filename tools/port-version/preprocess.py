"""Tiny source preprocessor for version-spanning platform code.

Directives (in any text/Java file):

    //#if MC >= 1.21.9 && FABRIC
    ...
    //#elif MC >= 1.20.5 || (FORGE && !MC < 1.19)
    ...
    //#else
    ...
    //#endif

Operands: ``MC <op> <version>`` with op in == != >= <= > <, and the loader
flags FABRIC, FORGE, LEGACYFABRIC. Operators: ``&&``, ``||``, ``!`` and
parentheses. Inactive lines and directive lines become empty lines so that
compiler line numbers match the source tree.
"""
from __future__ import annotations

import re

TOKEN = re.compile(r"\s*(MC|FABRIC|FORGE|LEGACYFABRIC|&&|\|\||!=|==|>=|<=|>|<|!|\(|\)|\d+(?:\.\d+)*)")


def vkey(v: str):
    return tuple(int(p) for p in re.findall(r"\d+", v))


def _cmp(a, op, b):
    # pad to equal length so 1.21 == 1.21.0
    n = max(len(a), len(b))
    a = a + (0,) * (n - len(a))
    b = b + (0,) * (n - len(b))
    return {"==": a == b, "!=": a != b, ">=": a >= b, "<=": a <= b, ">": a > b, "<": a < b}[op]


class _Parser:
    def __init__(self, expr: str, mc: str, loader: str):
        self.toks = []
        pos = 0
        expr = expr.strip()
        while pos < len(expr):
            m = TOKEN.match(expr, pos)
            if not m:
                raise ValueError(f"bad token in condition {expr!r} at {pos}")
            self.toks.append(m.group(1))
            pos = m.end()
        self.i = 0
        self.mc = vkey(mc)
        self.flags = {"FABRIC": loader == "fabric", "FORGE": loader == "forge", "LEGACYFABRIC": loader == "legacyfabric"}

    def peek(self):
        return self.toks[self.i] if self.i < len(self.toks) else None

    def take(self, t=None):
        tok = self.peek()
        if t is not None and tok != t:
            raise ValueError(f"expected {t}, got {tok}")
        self.i += 1
        return tok

    def parse(self):
        v = self.or_()
        if self.peek() is not None:
            raise ValueError(f"trailing tokens: {self.toks[self.i:]}")
        return v

    def or_(self):
        v = self.and_()
        while self.peek() == "||":
            self.take()
            v = self.and_() or v
        return v

    def and_(self):
        v = self.unary()
        while self.peek() == "&&":
            self.take()
            r = self.unary()
            v = v and r
        return v

    def unary(self):
        if self.peek() == "!":
            self.take()
            return not self.unary()
        if self.peek() == "(":
            self.take()
            v = self.or_()
            self.take(")")
            return v
        tok = self.take()
        if tok == "MC":
            op = self.take()
            ver = self.take()
            return _cmp(self.mc, op, vkey(ver))
        if tok in self.flags:
            return self.flags[tok]
        raise ValueError(f"unexpected token {tok}")


def evaluate(expr: str, mc: str, loader: str) -> bool:
    return _Parser(expr, mc, loader).parse()


DIRECTIVE = re.compile(r"^\s*//#(if|elif|else|endif)\b(.*)$")


def process(text: str, mc: str, loader: str, name: str = "<source>") -> str:
    out = []
    stack = []  # each: [parent_active, branch_taken, current_active]
    active = True
    for n, line in enumerate(text.splitlines(keepends=True), 1):
        m = DIRECTIVE.match(line)
        if not m:
            out.append(line if active else ("\n" if line.endswith("\n") else ""))
            continue
        kind, rest = m.group(1), m.group(2).strip()
        try:
            if kind == "if":
                cond = active and evaluate(rest, mc, loader)
                stack.append([active, cond])
                active = cond
            elif kind == "elif":
                if not stack:
                    raise ValueError("#elif without #if")
                parent, taken = stack[-1]
                cond = parent and not taken and evaluate(rest, mc, loader)
                stack[-1][1] = taken or cond
                active = cond
            elif kind == "else":
                if not stack:
                    raise ValueError("#else without #if")
                parent, taken = stack[-1]
                active = parent and not taken
                stack[-1][1] = True
            else:
                if not stack:
                    raise ValueError("#endif without #if")
                active = stack.pop()[0]
        except ValueError as e:
            raise SystemExit(f"{name}:{n}: {e}")
        out.append("\n")
    if stack:
        raise SystemExit(f"{name}: unterminated #if")
    return "".join(out)
