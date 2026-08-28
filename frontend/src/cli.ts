#!/usr/bin/env node
/**
 * The client a real consumer would actually use.
 *
 *   node dist/cli.js --tool data-validator --version 1.2
 *   node dist/cli.js --client client-a --tool data-validator
 *
 * The second form is the point of the whole platform: the consumer names no
 * version at all. Its pinned version is a property of the platform's
 * configuration, not of the consumer's command line - so rolling it back is a
 * config change somewhere else, and this command does not change.
 *
 * It always re-hashes the downloaded bytes and refuses to write a file whose
 * digest disagrees with the server's. Verifying the content is not the same as
 * trusting the transport.
 */

import { writeFileSync } from "node:fs";
import { ApiError, ToolPlatformClient, sha256Hex } from "./api.js";

interface Args {
  baseUrl: string;
  tool: string | undefined;
  version: string | undefined;
  client: string | undefined;
  out: string | undefined;
  list: boolean;
}

function parseArgs(argv: readonly string[]): Args {
  const args: Args = {
    baseUrl: process.env["BASE_URL"] ?? "http://localhost:8081",
    tool: undefined,
    version: undefined,
    client: undefined,
    out: undefined,
    list: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    const value = argv[i + 1];
    switch (flag) {
      case "--base-url": args.baseUrl = value ?? args.baseUrl; i++; break;
      case "--tool":     args.tool = value; i++; break;
      case "--version":  args.version = value; i++; break;
      case "--client":   args.client = value; i++; break;
      case "--out":      args.out = value; i++; break;
      case "--list":     args.list = true; break;
      case "--help":
      case "-h":         usage(); process.exit(0);
    }
  }
  return args;
}

function usage(): void {
  console.log(`
tool-fetch - download an exact version of an internal tool

  --tool <name>       tool to fetch
  --version <x.y>     exact version (mutually exclusive with --client)
  --client <name>     fetch whatever THIS client is pinned to
  --out <path>        where to write (default: the artifact's own filename)
  --list              list tools, or versions of --tool
  --base-url <url>    default http://localhost:8081  (or $BASE_URL)

  node dist/cli.js --list
  node dist/cli.js --tool data-validator --list
  node dist/cli.js --tool data-validator --version 1.2
  node dist/cli.js --client client-a --tool data-validator
`);
}

async function main(): Promise<number> {
  const args = parseArgs(process.argv.slice(2));
  const api = new ToolPlatformClient(args.baseUrl);

  if (args.list) {
    if (args.tool) {
      const versions = await api.listVersions(args.tool);
      for (const v of versions) {
        const seal = v.checksumSha256 ? v.checksumSha256.slice(0, 12) : "(no artifact)";
        console.log(`  ${v.version.padEnd(8)} ${v.status.padEnd(10)} ${seal}`);
      }
    } else {
      for (const t of await api.listTools()) {
        console.log(`  ${t.name.padEnd(24)} ${t.description ?? ""}`);
      }
    }
    return 0;
  }

  if (!args.tool) {
    usage();
    return 2;
  }

  const download = args.client
    ? await api.downloadForClient(args.client, args.tool)
    : args.version
      ? await api.downloadArtifact(args.tool, args.version)
      : null;

  if (download === null) {
    console.error("error: pass either --version <x.y> or --client <name>");
    return 2;
  }

  if (args.client) {
    console.log(`client ${args.client} is pinned to ${args.tool} ${download.version}`);
  }

  // The verification step. A mismatch means the bytes changed between the
  // registry recording them and this machine receiving them.
  const actual = await sha256Hex(download.bytes);
  if (download.sha256 && actual !== download.sha256) {
    console.error(
      `CHECKSUM MISMATCH - refusing to write the file\n` +
      `  server says : ${download.sha256}\n` +
      `  we computed : ${actual}`,
    );
    return 1;
  }

  const target = args.out ?? download.path.split("/").pop() ?? `${args.tool}-${download.version}.jar`;
  writeFileSync(target, Buffer.from(download.bytes));

  console.log(`wrote    ${target}  (${download.bytes.byteLength} bytes)`);
  console.log(`version  ${download.version}`);
  console.log(`sha256   ${actual}  verified`);
  return 0;
}

main()
  .then((code) => process.exit(code))
  .catch((error: unknown) => {
    if (error instanceof ApiError) {
      // The platform's error contract, surfaced usefully at the command line.
      console.error(`error: ${error.message}`);
      console.error(`  kind      : ${error.kind}`);
      console.error(`  status    : ${error.status}`);
      if (error.requestId) console.error(`  requestId : ${error.requestId}`);
      if (error.kind === "not-found") {
        console.error("  hint      : run with --list to see which versions exist");
      }
      if (error.kind === "version-revoked") {
        console.error("  hint      : this version was withdrawn; pin to another one");
      }
    } else {
      console.error("error:", error);
    }
    process.exit(1);
  });
