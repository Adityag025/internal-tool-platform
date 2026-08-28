/**
 * The three-step dashboard: pick a tool, pick a version, request it.
 *
 * Vanilla TypeScript and the DOM - no framework, no bundler, no state library.
 * This exists to demonstrate the platform's client contract, not to be a
 * frontend project, and reaching for React here would add hundreds of
 * dependencies to render two dropdowns.
 */

import { ApiError, ToolPlatformClient, sha256Hex, type ToolVersion } from "./api.js";

const api = new ToolPlatformClient(
  new URLSearchParams(location.search).get("api") ?? "http://localhost:8081",
);

const el = <T extends HTMLElement>(id: string): T => {
  const found = document.getElementById(id);
  if (!found) throw new Error(`missing element #${id}`);
  return found as T;
};

const toolSelect = el<HTMLSelectElement>("tool");
const versionSelect = el<HTMLSelectElement>("version");
const requestButton = el<HTMLButtonElement>("request");
const output = el<HTMLDivElement>("output");

function show(html: string, kind: "ok" | "err" | "info" = "info"): void {
  output.className = `output ${kind}`;
  output.innerHTML = html;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c] ?? c);
}

// --- step 1: which tools exist? --------------------------------------------
async function loadTools(): Promise<void> {
  try {
    const tools = await api.listTools();
    toolSelect.innerHTML = `<option value="">select a tool...</option>`;
    for (const tool of tools) {
      toolSelect.insertAdjacentHTML(
        "beforeend",
        `<option value="${escapeHtml(tool.name)}">${escapeHtml(tool.name)}</option>`,
      );
    }
    show(`${tools.length} tool(s) registered. Pick one.`);
  } catch (error) {
    reportError(error);
  }
}

// --- step 2: which versions of it? -----------------------------------------
async function loadVersions(tool: string): Promise<void> {
  versionSelect.innerHTML = "";
  versionSelect.disabled = true;
  requestButton.disabled = true;
  if (!tool) return;

  try {
    const versions = await api.listVersions(tool);
    // A REVOKED version is shown but not selectable: hiding it would leave a
    // user wondering where their version went. Say it was withdrawn.
    versionSelect.innerHTML = `<option value="">select a version...</option>`;
    for (const v of versions) {
      const label = `${v.version}  ${statusBadge(v)}`;
      const disabled = v.status === "REVOKED" ? " disabled" : "";
      versionSelect.insertAdjacentHTML(
        "beforeend",
        `<option value="${escapeHtml(v.version)}"${disabled}>${escapeHtml(label)}</option>`,
      );
    }
    versionSelect.disabled = false;
    show(`${versions.length} version(s) of <code>${escapeHtml(tool)}</code>. Pick an exact one.`);
  } catch (error) {
    reportError(error);
  }
}

function statusBadge(v: ToolVersion): string {
  switch (v.status) {
    case "PUBLISHED":  return v.checksumSha256 ? "" : "(no artifact)";
    case "DEPRECATED": return "- deprecated";
    case "REVOKED":    return "- REVOKED, unavailable";
    case "DRAFT":      return "- draft";
  }
}

// --- step 3: request that exact version ------------------------------------
async function requestVersion(tool: string, version: string): Promise<void> {
  requestButton.disabled = true;
  show("requesting...");
  const started = performance.now();

  try {
    const download = await api.downloadArtifact(tool, version);
    const actual = await sha256Hex(download.bytes);
    const elapsed = Math.round(performance.now() - started);
    const verified = actual === download.sha256;

    show(
      `<strong>${verified ? "Verified" : "CHECKSUM MISMATCH"}</strong>
       <table>
         <tr><td>tool</td><td><code>${escapeHtml(tool)}</code></td></tr>
         <tr><td>version</td><td><code>${escapeHtml(download.version)}</code></td></tr>
         <tr><td>path</td><td><code>${escapeHtml(download.path)}</code></td></tr>
         <tr><td>size</td><td>${download.bytes.byteLength} bytes</td></tr>
         <tr><td>server sha256</td><td><code>${escapeHtml(download.sha256)}</code></td></tr>
         <tr><td>recomputed</td><td><code>${escapeHtml(actual)}</code></td></tr>
         <tr><td>latency</td><td>${elapsed} ms</td></tr>
       </table>`,
      verified ? "ok" : "err",
    );

    if (verified) offerDownload(download.bytes, download.path.split("/").pop() ?? "artifact.jar");
  } catch (error) {
    reportError(error);
  } finally {
    requestButton.disabled = false;
  }
}

function offerDownload(bytes: ArrayBuffer, filename: string): void {
  const url = URL.createObjectURL(new Blob([bytes], { type: "application/octet-stream" }));
  output.insertAdjacentHTML(
    "beforeend",
    `<p><a href="${url}" download="${escapeHtml(filename)}">save ${escapeHtml(filename)}</a></p>`,
  );
}

function reportError(error: unknown): void {
  if (error instanceof ApiError) {
    show(
      `<strong>${escapeHtml(error.problem?.title ?? "Request failed")}</strong>
       <table>
         <tr><td>status</td><td>${error.status}</td></tr>
         <tr><td>type</td><td><code>${escapeHtml(error.kind)}</code></td></tr>
         <tr><td>detail</td><td>${escapeHtml(error.problem?.detail ?? "")}</td></tr>
         <tr><td>requestId</td><td><code>${escapeHtml(error.requestId ?? "-")}</code></td></tr>
       </table>`,
      "err",
    );
  } else {
    show(`<strong>Could not reach the platform.</strong>
          <p>Is it running on <code>http://localhost:8081</code>?</p>
          <p><code>${escapeHtml(String(error))}</code></p>`, "err");
  }
}

toolSelect.addEventListener("change", () => void loadVersions(toolSelect.value));
versionSelect.addEventListener("change", () => {
  requestButton.disabled = versionSelect.value === "";
});
requestButton.addEventListener("click", () => {
  void requestVersion(toolSelect.value, versionSelect.value);
});

void loadTools();
