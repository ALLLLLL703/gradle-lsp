#!/usr/bin/env node

import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const root = process.cwd();
const executable = resolve(root, "build/install/gradle-lsp/bin/gradle-lsp");
const scriptPath = resolve(root, "build.gradle.kts");
const scriptText = await readFile(scriptPath, "utf8");
const scriptUri = pathToFileURL(scriptPath).href;
const rootUri = pathToFileURL(`${root}/`).href;
const maximumRssKiB = 1024 * 1024;

const server = spawn(executable, ["--stdio"], {
  cwd: root,
  stdio: ["pipe", "pipe", "inherit"],
});

let buffered = Buffer.alloc(0);
let nextRequestId = 1;
const pendingRequests = new Map();
let diagnosticsReceived;
const diagnostics = new Promise((resolveDiagnostics) => {
  diagnosticsReceived = resolveDiagnostics;
});

server.stdout.on("data", (chunk) => {
  buffered = Buffer.concat([buffered, chunk]);
  while (true) {
    const headerEnd = buffered.indexOf("\r\n\r\n");
    if (headerEnd < 0) return;
    const header = buffered.subarray(0, headerEnd).toString("ascii");
    const lengthMatch = /Content-Length: (\d+)/i.exec(header);
    if (lengthMatch === null) throw new Error(`Missing Content-Length in ${header}`);
    const contentLength = Number(lengthMatch[1]);
    const messageEnd = headerEnd + 4 + contentLength;
    if (buffered.length < messageEnd) return;

    const message = JSON.parse(buffered.subarray(headerEnd + 4, messageEnd).toString("utf8"));
    buffered = buffered.subarray(messageEnd);
    if (message.id !== undefined && pendingRequests.has(message.id)) {
      const complete = pendingRequests.get(message.id);
      pendingRequests.delete(message.id);
      if (message.error === undefined) complete.resolve(message.result);
      else complete.reject(new Error(JSON.stringify(message.error)));
    } else if (message.method === "textDocument/publishDiagnostics") {
      diagnosticsReceived(message.params);
    }
  }
});

function send(message) {
  const body = JSON.stringify({ jsonrpc: "2.0", ...message });
  server.stdin.write(`Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
}

function request(method, params) {
  const id = nextRequestId++;
  send({ id, method, params });
  return new Promise((resolveRequest, rejectRequest) => {
    pendingRequests.set(id, { resolve: resolveRequest, reject: rejectRequest });
  });
}

function notify(method, params) {
  send({ method, params });
}

async function withTimeout(promise, timeoutMs, description) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${description} timed out after ${timeoutMs} ms`)), timeoutMs);
  });
  try {
    return await Promise.race([promise, timeout]);
  } finally {
    clearTimeout(timer);
  }
}

function positionOf(needle, occurrence = 1) {
  let offset = -1;
  for (let index = 0; index < occurrence; index += 1) {
    offset = scriptText.indexOf(needle, offset + 1);
  }
  if (offset < 0) throw new Error(`Could not find ${needle}`);
  const prefix = scriptText.slice(0, offset);
  const lines = prefix.split("\n");
  return { line: lines.length - 1, character: lines.at(-1).length };
}

function withDocument(position) {
  return { textDocument: { uri: scriptUri }, position };
}

try {
  await request("initialize", {
    processId: process.pid,
    rootUri,
    workspaceFolders: [{ uri: rootUri, name: "gradle-lsp" }],
    capabilities: {},
  });
  notify("initialized", {});
  notify("textDocument/didOpen", {
    textDocument: {
      uri: scriptUri,
      languageId: "kotlin",
      version: 1,
      text: scriptText,
    },
  });

  await Promise.race([
    diagnostics,
    new Promise((_, reject) => setTimeout(() => reject(new Error("Diagnostics timed out")), 180_000)),
  ]);
  await request("textDocument/documentSymbol", { textDocument: { uri: scriptUri } });
  await request("textDocument/definition", withDocument(positionOf("kotlinStdlibSources", 2)));

  const externalDefinitionDurationsMs = [];
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const startedAt = performance.now();
    const externalDefinition = await withTimeout(
      request("textDocument/definition", withDocument(positionOf("implementation("))),
      30_000,
      `Gradle implementation definition attempt ${attempt}`,
    );
    externalDefinitionDurationsMs.push(Math.round(performance.now() - startedAt));
    const hasExternalDefinition = Array.isArray(externalDefinition) && externalDefinition.some((location) => {
      const uri = location.uri ?? location.targetUri;
      return typeof uri === "string" && uri.startsWith("gradle-lsp://source/");
    });
    if (!hasExternalDefinition) throw new Error("Gradle implementation did not resolve to external source");
  }

  await request("textDocument/declaration", withDocument(positionOf("kotlinStdlibSources", 2)));
  await request("textDocument/typeDefinition", withDocument(positionOf("configurations")));
  await request("textDocument/references", {
    ...withDocument(positionOf("kotlinStdlibSources", 2)),
    context: { includeDeclaration: true },
  });
  await request("textDocument/implementation", withDocument(positionOf("mavenCentral")));

  const processStatus = await readFile(`/proc/${server.pid}/status`, "utf8");
  const currentRssKiB = Number(/^VmRSS:\s+(\d+)\s+kB$/m.exec(processStatus)?.[1]);
  const peakRssKiB = Number(/^VmHWM:\s+(\d+)\s+kB$/m.exec(processStatus)?.[1]);
  const threads = Number(/^Threads:\s+(\d+)$/m.exec(processStatus)?.[1]);
  if (!Number.isFinite(peakRssKiB)) throw new Error("VmHWM is unavailable; this check requires Linux /proc");

  console.log(JSON.stringify({
    currentRssKiB,
    peakRssKiB,
    threads,
    maximumRssKiB,
    externalDefinitionDurationsMs,
  }));
  if (peakRssKiB >= maximumRssKiB) {
    throw new Error(`Peak RSS ${peakRssKiB} KiB exceeded ${maximumRssKiB} KiB`);
  }

  await request("shutdown", null);
  notify("exit");
  await new Promise((resolveExit) => server.once("exit", resolveExit));
} catch (failure) {
  server.kill("SIGKILL");
  throw failure;
}
