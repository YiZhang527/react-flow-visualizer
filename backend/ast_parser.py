"""
AST parser module — extracts imports, exports, functions, and calls from source files.
Currently supports JavaScript/TypeScript via tree-sitter.
Designed for extension: add a new subclass of LanguageParser for each language.
"""

from __future__ import annotations

import os
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import tree_sitter_javascript as ts_js
import tree_sitter_typescript as ts_ts
from tree_sitter import Language, Parser, Node

JS_LANGUAGE = Language(ts_js.language())
TS_LANGUAGE = Language(ts_ts.language_typescript())
TSX_LANGUAGE = Language(ts_ts.language_tsx())


@dataclass
class FileAnalysis:
    file_path: str
    imports: list[dict] = field(default_factory=list)   # [{"from": "./foo", "name": "Foo"}]
    exports: list[str] = field(default_factory=list)     # ["getUser", "default"]
    functions: list[str] = field(default_factory=list)   # ["getUser(id)", "createUser(data)"]
    calls: list[str] = field(default_factory=list)       # ["UserService.getUser", "fetch"]

    def structural_signature(self) -> tuple:
        return (
            tuple(sorted((d["from"], d["name"]) for d in self.imports)),
            tuple(sorted(self.exports)),
            tuple(sorted(self.functions)),
        )

    def to_dict(self) -> dict:
        return {
            "file_path": self.file_path,
            "imports": self.imports,
            "exports": self.exports,
            "functions": self.functions,
            "calls": self.calls,
        }


class LanguageParser(ABC):
    @abstractmethod
    def can_parse(self, file_path: str) -> bool: ...

    @abstractmethod
    def parse(self, file_path: str, source: bytes) -> FileAnalysis: ...


class JSTypeScriptParser(LanguageParser):
    """Handles .js, .jsx, .ts, .tsx files using tree-sitter."""

    _EXT_LANG = {
        ".js": JS_LANGUAGE,
        ".mjs": JS_LANGUAGE,
        ".cjs": JS_LANGUAGE,
        ".jsx": JS_LANGUAGE,
        ".ts": TS_LANGUAGE,
        ".tsx": TSX_LANGUAGE,
    }

    def can_parse(self, file_path: str) -> bool:
        return Path(file_path).suffix.lower() in self._EXT_LANG

    def parse(self, file_path: str, source: bytes) -> FileAnalysis:
        ext = Path(file_path).suffix.lower()
        lang = self._EXT_LANG[ext]
        parser = Parser(lang)
        tree = parser.parse(source)
        root = tree.root_node

        analysis = FileAnalysis(file_path=file_path)
        self._extract_imports(root, source, analysis)
        self._extract_exports(root, source, analysis)
        self._extract_functions(root, source, analysis)
        self._extract_calls(root, source, analysis)
        return analysis

    def _text(self, node: Node, source: bytes) -> str:
        return source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")

    def _extract_imports(self, root: Node, source: bytes, result: FileAnalysis):
        for node in self._walk(root):
            if node.type == "import_statement":
                from_clause = None
                names: list[str] = []
                for child in node.children:
                    if child.type == "string" or child.type == "string_fragment":
                        from_clause = self._text(child, source).strip("'\"")
                    elif child.type == "import_clause":
                        names.extend(self._import_clause_names(child, source))
                if from_clause is None:
                    for child in node.children:
                        if child.type == "string" or child.type.endswith("string"):
                            from_clause = self._text(child, source).strip("'\"")
                if from_clause:
                    if not names:
                        names = [Path(from_clause).stem]
                    for n in names:
                        result.imports.append({"from": from_clause, "name": n})

    def _import_clause_names(self, node: Node, source: bytes) -> list[str]:
        names = []
        for child in node.children:
            if child.type == "identifier":
                names.append(self._text(child, source))
            elif child.type == "named_imports":
                for spec in child.children:
                    if spec.type == "import_specifier":
                        alias = None
                        orig = None
                        for c in spec.children:
                            if c.type == "identifier":
                                if orig is None:
                                    orig = self._text(c, source)
                                else:
                                    alias = self._text(c, source)
                        names.append(alias or orig or "")
            elif child.type == "namespace_import":
                for c in child.children:
                    if c.type == "identifier":
                        names.append(self._text(c, source))
        return names

    def _extract_exports(self, root: Node, source: bytes, result: FileAnalysis):
        for node in self._walk(root):
            if node.type == "export_statement":
                is_default = any(
                    c.type == "default" or self._text(c, source) == "default"
                    for c in node.children
                )
                for child in node.children:
                    if child.type in ("function_declaration", "class_declaration"):
                        name_node = child.child_by_field_name("name")
                        if name_node:
                            result.exports.append(self._text(name_node, source))
                        elif is_default:
                            result.exports.append("default")
                    elif child.type == "lexical_declaration":
                        for decl in child.children:
                            if decl.type == "variable_declarator":
                                name_node = decl.child_by_field_name("name")
                                if name_node:
                                    result.exports.append(self._text(name_node, source))
                    elif child.type == "export_clause":
                        for spec in child.children:
                            if spec.type == "export_specifier":
                                for c in spec.children:
                                    if c.type == "identifier":
                                        result.exports.append(self._text(c, source))
                                        break
                if is_default and "default" not in result.exports:
                    found_named = False
                    for child in node.children:
                        if child.type in (
                            "function_declaration", "class_declaration",
                            "lexical_declaration", "export_clause",
                        ):
                            found_named = True
                    if not found_named:
                        result.exports.append("default")

    def _extract_functions(self, root: Node, source: bytes, result: FileAnalysis):
        for node in self._walk(root):
            if node.type in ("function_declaration", "method_definition"):
                name_node = node.child_by_field_name("name")
                params_node = node.child_by_field_name("parameters")
                if name_node:
                    name = self._text(name_node, source)
                    params = self._format_params(params_node, source) if params_node else ""
                    result.functions.append(f"{name}({params})")
            elif node.type == "variable_declarator":
                name_node = node.child_by_field_name("name")
                value_node = node.child_by_field_name("value")
                if name_node and value_node and value_node.type in (
                    "arrow_function", "function_expression", "function"
                ):
                    name = self._text(name_node, source)
                    params_node = value_node.child_by_field_name("parameters")
                    params = self._format_params(params_node, source) if params_node else ""
                    result.functions.append(f"{name}({params})")

    def _format_params(self, params_node: Node, source: bytes) -> str:
        names = []
        for child in params_node.children:
            if child.type in ("identifier", "required_parameter", "optional_parameter"):
                ident = child.child_by_field_name("pattern") or child
                if ident.type == "identifier" or child.type == "identifier":
                    names.append(self._text(ident if ident.type == "identifier" else child, source))
            elif child.type == "rest_pattern":
                for c in child.children:
                    if c.type == "identifier":
                        names.append(f"...{self._text(c, source)}")
        return ", ".join(names)

    def _extract_calls(self, root: Node, source: bytes, result: FileAnalysis):
        seen = set()
        for node in self._walk(root):
            if node.type == "call_expression":
                fn_node = node.child_by_field_name("function")
                if fn_node:
                    call_name = self._text(fn_node, source).strip()
                    if not call_name.startswith("(") and call_name not in seen:
                        call_name = re.sub(r"\s+", "", call_name)
                        seen.add(call_name)
                        result.calls.append(call_name)

    def _walk(self, node: Node):
        yield node
        for child in node.children:
            yield from self._walk(child)


_PARSERS: list[LanguageParser] = [
    JSTypeScriptParser(),
]

SUPPORTED_EXTENSIONS = {".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx"}


def is_supported(file_path: str) -> bool:
    return any(p.can_parse(file_path) for p in _PARSERS)


def parse_file(file_path: str) -> Optional[FileAnalysis]:
    if not os.path.isfile(file_path):
        return None
    with open(file_path, "rb") as f:
        source = f.read()
    for p in _PARSERS:
        if p.can_parse(file_path):
            return p.parse(file_path, source)
    return None


def should_skip_dir(dir_name: str) -> bool:
    return dir_name in {
        "node_modules", ".git", ".svn", "dist", "build",
        ".next", ".nuxt", "__pycache__", ".venv", "venv",
        ".idea", ".vscode", "coverage", ".cache",
    }
