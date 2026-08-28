/**
 * Typed client for the Tool Platform API.
 *
 * Shared by the browser dashboard and the Node CLI, so the API contract is
 * written down exactly once. This is what TypeScript is actually buying here:
 * if the service renames a field, this file fails to compile rather than
 * quietly rendering `undefined` into a page or a download.
 */

export type VersionStatus = "DRAFT" | "PUBLISHED" | "DEPRECATED" | "REVOKED";
export type VersionSelector = "PINNED" | "LATEST";

export interface Tool {
  readonly id: number;
  readonly name: string;
  readonly description: string | null;
  readonly createdAt: string;
}

export interface ToolVersion {
  readonly id: number;
  readonly tool: string;
  readonly version: string;
  readonly artifactPath: string;
  readonly checksumSha256: string | null;
  readonly status: VersionStatus;
  readonly createdAt: string;
}

export interface ResolvedVersion {
  readonly client: string;
  readonly tool: string;
  readonly selector: VersionSelector;
  readonly resolvedVersion: string;
  readonly artifactPath: string;
  readonly checksumSha256: string | null;
  readonly status: VersionStatus;
  readonly deprecated: boolean;
}

export interface PageResponse<T> {
  readonly data: readonly T[];
  readonly pagination: {
    readonly page: number;
    readonly size: number;
    readonly totalElements: number;
    readonly totalPages: number;
    readonly hasMore: boolean;
  };
}

/** RFC 7807 problem+json, exactly as the service emits it. */
export interface Problem {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly requestId?: string;
  readonly errors?: ReadonlyArray<{ field: string; message: string }>;
}

/**
 * Thrown for any non-2xx response, carrying the parsed problem body.
 *
 * The `requestId` is deliberately surfaced: it is the same id in the service's
 * access log, so a user can paste it into a ticket and it can be grepped.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem | null,
    readonly requestId: string | null,
  ) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
    this.name = "ApiError";
  }

  /** The stable machine-readable identifier, e.g. "not-found". */
  get kind(): string {
    return this.problem?.type?.split("/").pop() ?? "unknown";
  }
}

export interface ArtifactDownload {
  readonly bytes: ArrayBuffer;
  readonly version: string;
  readonly sha256: string;
  readonly path: string;
}

export class ToolPlatformClient {
  constructor(private readonly baseUrl: string = "http://localhost:8081") {
    this.baseUrl = baseUrl.replace(/\/$/, "");
  }

  private async request(path: string, init?: RequestInit): Promise<Response> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: { "X-Client-Id": "typescript-client", ...(init?.headers ?? {}) },
    });

    if (!response.ok) {
      let problem: Problem | null = null;
      try {
        problem = (await response.json()) as Problem;
      } catch {
        // Not every failure has a JSON body (a proxy 502, for instance).
      }
      throw new ApiError(response.status, problem, response.headers.get("X-Request-Id"));
    }
    return response;
  }

  async listTools(): Promise<readonly Tool[]> {
    const response = await this.request("/api/v1/tools");
    return ((await response.json()) as PageResponse<Tool>).data;
  }

  async listVersions(tool: string): Promise<readonly ToolVersion[]> {
    const response = await this.request(`/api/v1/tools/${encodeURIComponent(tool)}/versions`);
    return (await response.json()) as ToolVersion[];
  }

  /** Exact resolution. A version that does not exist throws, it never substitutes. */
  async getVersion(tool: string, version: string): Promise<ToolVersion> {
    const response = await this.request(
      `/api/v1/tools/${encodeURIComponent(tool)}/versions/${encodeURIComponent(version)}`,
    );
    return (await response.json()) as ToolVersion;
  }

  async resolveForClient(client: string, tool: string): Promise<ResolvedVersion> {
    const response = await this.request(
      `/api/v1/clients/${encodeURIComponent(client)}/tools/${encodeURIComponent(tool)}/version`,
    );
    return (await response.json()) as ResolvedVersion;
  }

  /**
   * Download an exact version's bytes.
   *
   * The server's SHA-256 comes back in a header; callers are expected to
   * re-hash the bytes themselves. Trusting the transport is not the same as
   * verifying the content.
   */
  async downloadArtifact(tool: string, version: string): Promise<ArtifactDownload> {
    const response = await this.request(
      `/api/v1/tools/${encodeURIComponent(tool)}/versions/${encodeURIComponent(version)}/artifact`,
    );
    return {
      bytes: await response.arrayBuffer(),
      version: response.headers.get("X-Artifact-Version") ?? version,
      sha256: response.headers.get("X-Artifact-Sha256") ?? "",
      path: response.headers.get("X-Artifact-Path") ?? "",
    };
  }

  /** What this client is configured to receive - no version named by the caller. */
  async downloadForClient(client: string, tool: string): Promise<ArtifactDownload> {
    const response = await this.request(
      `/api/v1/clients/${encodeURIComponent(client)}/tools/${encodeURIComponent(tool)}/artifact`,
    );
    return {
      bytes: await response.arrayBuffer(),
      version: response.headers.get("X-Artifact-Version") ?? "",
      sha256: response.headers.get("X-Artifact-Sha256") ?? "",
      path: response.headers.get("X-Artifact-Path") ?? "",
    };
  }
}

/** Hex SHA-256 of the given bytes. Works in the browser and in Node 18+. */
export async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
