"""A thin, typed client for the Tool Platform REST API.

Why wrap `requests` at all? Because without this layer every test would repeat
URL construction, headers, and timeouts. When the API changes - a path, a verb,
an auth header - you fix it here once instead of in forty test functions.

It is deliberately DUMB: it never asserts and never raises on HTTP errors. A
4xx is a perfectly valid outcome that many tests expect. Judgement belongs in
assertions.py, transport belongs here.
"""

from __future__ import annotations

import hashlib
from typing import Any

import requests


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class ToolPlatformClient:

    def __init__(self, base_url: str, timeout: float = 15.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        # Tag every request so a failure can be traced straight into the
        # service's access log: grep the requestId, see method/status/latency.
        self.session.headers.update({"X-Client-Id": "pytest-integration-suite"})

    # ------------------------------------------------------------- transport

    def request(self, method: str, path: str, **kwargs: Any) -> requests.Response:
        kwargs.setdefault("timeout", self.timeout)
        return self.session.request(method, f"{self.base_url}{path}", **kwargs)

    def get(self, path: str, **kw: Any) -> requests.Response:
        return self.request("GET", path, **kw)

    def post(self, path: str, **kw: Any) -> requests.Response:
        return self.request("POST", path, **kw)

    def put(self, path: str, **kw: Any) -> requests.Response:
        return self.request("PUT", path, **kw)

    def delete(self, path: str, **kw: Any) -> requests.Response:
        return self.request("DELETE", path, **kw)

    # ----------------------------------------------------------------- health

    def health(self) -> requests.Response:
        return self.get("/actuator/health")

    # ------------------------------------------------------------------ tools

    def create_tool(self, name: str, description: str | None = None) -> requests.Response:
        return self.post("/api/v1/tools", json={"name": name, "description": description})

    def get_tool(self, name: str) -> requests.Response:
        return self.get(f"/api/v1/tools/{name}")

    def list_tools(self) -> requests.Response:
        return self.get("/api/v1/tools")

    # --------------------------------------------------------------- versions

    def publish_version(self, tool: str, version: str, artifact_path: str | None = None,
                        status: str | None = None, checksum: str | None = None) -> requests.Response:
        body: dict[str, Any] = {
            "version": version,
            "artifactPath": artifact_path if artifact_path is not None
            else f"{tool}/{version}/{tool}-{version}.jar",
        }
        if status is not None:
            body["status"] = status
        if checksum is not None:
            body["checksumSha256"] = checksum
        return self.post(f"/api/v1/tools/{tool}/versions", json=body)

    def get_version(self, tool: str, version: str) -> requests.Response:
        return self.get(f"/api/v1/tools/{tool}/versions/{version}")

    def list_versions(self, tool: str) -> requests.Response:
        return self.get(f"/api/v1/tools/{tool}/versions")

    def promote(self, tool: str, version: str, status: str,
                reason: str | None = None) -> requests.Response:
        return self.post(f"/api/v1/tools/{tool}/versions/{version}/promotion",
                         json={"status": status, "reason": reason})

    # --------------------------------------------------------------- artifacts

    def upload_artifact(self, tool: str, version: str, content: bytes) -> requests.Response:
        return self.put(f"/api/v1/tools/{tool}/versions/{version}/artifact",
                        data=content,
                        headers={"Content-Type": "application/octet-stream"})

    def download_artifact(self, tool: str, version: str) -> requests.Response:
        return self.get(f"/api/v1/tools/{tool}/versions/{version}/artifact")

    # ----------------------------------------------------------------- clients

    def create_client(self, name: str) -> requests.Response:
        return self.post("/api/v1/clients", json={"name": name})

    def set_client_version(self, client: str, tool: str, version: str) -> requests.Response:
        return self.put(f"/api/v1/clients/{client}/tools/{tool}/version",
                        json={"version": version})

    def get_client_version(self, client: str, tool: str) -> requests.Response:
        return self.get(f"/api/v1/clients/{client}/tools/{tool}/version")

    def download_for_client(self, client: str, tool: str) -> requests.Response:
        return self.get(f"/api/v1/clients/{client}/tools/{tool}/artifact")

    def delete_client_version(self, client: str, tool: str) -> requests.Response:
        return self.delete(f"/api/v1/clients/{client}/tools/{tool}/version")
