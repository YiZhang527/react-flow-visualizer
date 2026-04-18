"""
In-memory graph state with incremental update and diff support.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from ast_parser import (
    FileAnalysis,
    is_supported,
    parse_file,
    should_skip_dir,
    SUPPORTED_EXTENSIONS,
)


@dataclass
class GraphNode:
    id: str                          # normalized relative path (e.g. "src/services/userService.ts")
    label: str                       # file name without extension
    file_path: str                   # absolute path
    layer: str = "gray"              # purple / blue / green / gray
    description: str = ""
    functions: list[str] = field(default_factory=list)
    imports: list[dict] = field(default_factory=list)
    exports: list[str] = field(default_factory=list)
    calls: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "label": self.label,
            "filePath": self.file_path,
            "layer": self.layer,
            "description": self.description,
            "functions": self.functions,
            "imports": self.imports,
            "exports": self.exports,
            "calls": self.calls,
        }


@dataclass
class GraphEdge:
    source: str    # node id
    target: str    # node id
    label: str = ""

    def to_dict(self) -> dict:
        return {"source": self.source, "target": self.target, "label": self.label}


def _detect_layer(rel_path: str) -> str:
    """Heuristic layer detection based on path segments and file name."""
    low = rel_path.lower()
    parts = low.replace("\\", "/").split("/")
    name = parts[-1] if parts else ""

    for p in parts:
        if p in ("middleware", "middlewares", "controller", "controllers",
                 "routes", "route", "router", "routers", "api", "handler", "handlers"):
            return "purple"
        if p in ("service", "services", "use-cases", "usecases", "logic"):
            return "blue"
        if p in ("model", "models", "db", "database", "repo", "repository",
                 "repositories", "schema", "schemas", "migration", "migrations",
                 "prisma", "entity", "entities"):
            return "green"
        if p in ("util", "utils", "helpers", "helper", "lib", "libs",
                 "shared", "common", "config", "constants"):
            return "gray"

    if any(kw in name for kw in ("controller", "handler", "route", "middleware")):
        return "purple"
    if any(kw in name for kw in ("service", "usecase")):
        return "blue"
    if any(kw in name for kw in ("model", "repo", "schema", "entity", "db")):
        return "green"

    return "gray"


class ProjectGraph:
    def __init__(self):
        self.project_root: str = ""
        self.nodes: dict[str, GraphNode] = {}
        self.edges: list[GraphEdge] = []
        self._analysis_cache: dict[str, FileAnalysis] = {}

    def _rel(self, abs_path: str) -> str:
        try:
            return str(Path(abs_path).relative_to(self.project_root)).replace("\\", "/")
        except ValueError:
            return abs_path.replace("\\", "/")

    def _resolve_import(self, from_path: str, import_from: str) -> Optional[str]:
        """Resolve a relative import to a node id (relative path)."""
        if not import_from.startswith("."):
            return None

        dir_of_file = os.path.dirname(from_path)
        candidate = os.path.normpath(os.path.join(dir_of_file, import_from))

        for ext in ("", ".ts", ".tsx", ".js", ".jsx", ".mjs"):
            full = candidate + ext
            rel = self._rel(full)
            if rel in self.nodes:
                return rel
            idx = os.path.join(candidate, "index")
            for ie in (".ts", ".tsx", ".js", ".jsx"):
                irel = self._rel(idx + ie)
                if irel in self.nodes:
                    return irel

        return None

    def build(self, project_root: str) -> dict:
        """Full scan of project. Returns complete graph JSON."""
        self.project_root = os.path.abspath(project_root)
        self.nodes.clear()
        self.edges.clear()
        self._analysis_cache.clear()

        for dirpath, dirnames, filenames in os.walk(self.project_root):
            dirnames[:] = [d for d in dirnames if not should_skip_dir(d)]
            for fname in filenames:
                abs_path = os.path.join(dirpath, fname)
                if not is_supported(abs_path):
                    continue
                analysis = parse_file(abs_path)
                if analysis is None:
                    continue
                rel = self._rel(abs_path)
                self._analysis_cache[rel] = analysis
                node = GraphNode(
                    id=rel,
                    label=Path(fname).stem,
                    file_path=abs_path,
                    layer=_detect_layer(rel),
                    functions=analysis.functions,
                    imports=analysis.imports,
                    exports=analysis.exports,
                    calls=analysis.calls,
                )
                self.nodes[rel] = node

        self._rebuild_edges()
        return self.to_dict()

    def _rebuild_edges(self):
        self.edges.clear()
        for node_id, node in self.nodes.items():
            analysis = self._analysis_cache.get(node_id)
            if not analysis:
                continue
            for imp in analysis.imports:
                target = self._resolve_import(node.file_path, imp["from"])
                if target and target != node_id:
                    self.edges.append(GraphEdge(
                        source=node_id,
                        target=target,
                        label=imp.get("name", ""),
                    ))

    def update_file(self, abs_path: str) -> dict:
        """Re-analyze one file and return a diff."""
        rel = self._rel(abs_path)
        old_analysis = self._analysis_cache.get(rel)

        if not os.path.isfile(abs_path):
            return self._remove_node(rel)

        if not is_supported(abs_path):
            return {"structuralChange": False, "addedEdges": [], "removedEdges": [], "updatedNode": None}

        new_analysis = parse_file(abs_path)
        if new_analysis is None:
            return self._remove_node(rel)

        structural_change = (
            old_analysis is None
            or old_analysis.structural_signature() != new_analysis.structural_signature()
        )

        if not structural_change:
            return {"structuralChange": False, "addedEdges": [], "removedEdges": [], "updatedNode": None}

        self._analysis_cache[rel] = new_analysis

        old_edges = {(e.source, e.target, e.label) for e in self.edges if e.source == rel or e.target == rel}

        node = GraphNode(
            id=rel,
            label=Path(abs_path).stem,
            file_path=abs_path,
            layer=_detect_layer(rel),
            functions=new_analysis.functions,
            imports=new_analysis.imports,
            exports=new_analysis.exports,
            calls=new_analysis.calls,
        )
        self.nodes[rel] = node
        self._rebuild_edges()

        new_edges = {(e.source, e.target, e.label) for e in self.edges if e.source == rel or e.target == rel}

        added = [{"source": s, "target": t, "label": l} for s, t, l in new_edges - old_edges]
        removed = [{"source": s, "target": t, "label": l} for s, t, l in old_edges - new_edges]

        return {
            "structuralChange": True,
            "addedEdges": added,
            "removedEdges": removed,
            "updatedNode": node.to_dict(),
        }

    def _remove_node(self, rel: str) -> dict:
        removed_edges = [e.to_dict() for e in self.edges if e.source == rel or e.target == rel]
        self.edges = [e for e in self.edges if e.source != rel and e.target != rel]
        removed_node = self.nodes.pop(rel, None)
        self._analysis_cache.pop(rel, None)
        return {
            "structuralChange": True,
            "addedEdges": [],
            "removedEdges": removed_edges,
            "removedNode": removed_node.to_dict() if removed_node else None,
            "updatedNode": None,
        }

    def get_node_detail(self, node_id: str) -> Optional[dict]:
        node = self.nodes.get(node_id)
        if not node:
            return None

        calls_to = [
            {"target": e.target, "targetLabel": self.nodes[e.target].label, "label": e.label}
            for e in self.edges if e.source == node_id and e.target in self.nodes
        ]
        called_by = [
            {"source": e.source, "sourceLabel": self.nodes[e.source].label, "label": e.label}
            for e in self.edges if e.target == node_id and e.source in self.nodes
        ]

        detail = node.to_dict()
        detail["callsTo"] = calls_to
        detail["calledBy"] = called_by
        return detail

    def to_dict(self) -> dict:
        return {
            "nodes": [n.to_dict() for n in self.nodes.values()],
            "edges": [e.to_dict() for e in self.edges],
        }
