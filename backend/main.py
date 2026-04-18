"""
FastAPI backend for ArchMap — project architecture analysis.
Endpoints: POST /init, POST /update, GET /node/{node_id}
"""

import logging
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional

from graph_state import ProjectGraph

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("archmap")

app = FastAPI(title="ArchMap Backend")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

graph = ProjectGraph()


class InitRequest(BaseModel):
    projectRoot: str


class UpdateRequest(BaseModel):
    projectRoot: str
    filePath: str


@app.post("/init")
async def init_project(request: Request):
    try:
        body = await request.json()
        logger.info(f"/init received: {body}")
        project_root = body.get("projectRoot") or body.get("project_root") or body.get("root")
        if not project_root:
            return {"error": "projectRoot is required", "nodes": [], "edges": []}
        result = graph.build(project_root)
        logger.info(f"/init returning {len(result.get('nodes', []))} nodes, {len(result.get('edges', []))} edges")
        return result
    except Exception as e:
        logger.error(f"/init error: {e}")
        return {"error": str(e), "nodes": [], "edges": []}


@app.post("/update")
async def update_file(request: Request):
    try:
        body = await request.json()
        logger.info(f"/update received: {body}")
        project_root = body.get("projectRoot") or body.get("project_root")
        file_path = body.get("filePath") or body.get("file_path")
        if not file_path:
            return {"structuralChange": False, "addedEdges": [], "removedEdges": [], "updatedNode": None}
        if not graph.project_root and project_root:
            graph.build(project_root)
        diff = graph.update_file(file_path)
        return diff
    except Exception as e:
        logger.error(f"/update error: {e}")
        return {"structuralChange": False, "addedEdges": [], "removedEdges": [], "updatedNode": None}


@app.get("/node/{node_id:path}")
def get_node(node_id: str):
    detail = graph.get_node_detail(node_id)
    if detail is None:
        raise HTTPException(status_code=404, detail=f"Node '{node_id}' not found")
    return detail


@app.get("/health")
def health():
    return {"status": "ok", "nodes": len(graph.nodes), "edges": len(graph.edges)}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8742)
