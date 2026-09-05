#!/usr/bin/env node

import { execFileSync, spawn } from "node:child_process";
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
  const initialize = await request("initialize", {
    processId: process.pid,
    rootUri,
    workspaceFolders: [{ uri: rootUri, name: "gradle-lsp" }],
    capabilities: {},
  });
  if (initialize.capabilities.hoverProvider !== true) throw new Error("Server did not advertise hover support");
  if (!initialize.capabilities.completionProvider?.triggerCharacters?.includes(".")) {
    throw new Error("Server did not advertise import completion support");
  }
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

  const externalHoverStartedAt = performance.now();
  const externalHover = await withTimeout(
    request("textDocument/hover", withDocument(positionOf("implementation("))),
    30_000,
    "Gradle implementation hover",
  );
  const externalHoverMs = Math.round(performance.now() - externalHoverStartedAt);
  const hoverContents = externalHover?.contents;
  const hasKotlinSignature = Array.isArray(hoverContents) && hoverContents.some((content) =>
    content?.language === "kotlin" && content.value.includes("DependencyHandler.implementation"));
  const hasKDoc = Array.isArray(hoverContents) && hoverContents.some((content) =>
    typeof content === "string" && content.includes("Adds a dependency to the 'implementation' configuration."));
  if (!hasKotlinSignature || !hasKDoc) throw new Error("Gradle implementation hover is incomplete");

  await request("textDocument/declaration", withDocument(positionOf("kotlinStdlibSources", 2)));
  await request("textDocument/typeDefinition", withDocument(positionOf("configurations")));
  await request("textDocument/references", {
    ...withDocument(positionOf("kotlinStdlibSources", 2)),
    context: { includeDeclaration: true },
  });
  await request("textDocument/implementation", withDocument(positionOf("mavenCentral")));

  const importCompletionDurationsMs = [];
  let version = 1;
  for (const [importPath, expectedPackage, expectedInsertion] of [
    ["", "java", "java."],
    ["org.gr", "org.gradle", "gradle."],
    ["org.gradle.", "org.gradle.api", "api."],
    ["kotlin.col", "kotlin.collections", "collections."],
    ["java.ut", "java.util", "util."],
  ]) {
    const importLine = `/* 😀 */ import ${importPath}`;
    notify("textDocument/didChange", {
      textDocument: { uri: scriptUri, version: ++version },
      contentChanges: [{ text: `${importLine}\n${scriptText}\nval broken =` }],
    });
    const startedAt = performance.now();
    const completions = await withTimeout(
      request("textDocument/completion", withDocument({ line: 0, character: importLine.length })),
      30_000,
      `Import package completion for ${importPath}`,
    );
    importCompletionDurationsMs.push(Math.round(performance.now() - startedAt));
    const candidate = completions?.items?.find((item) => item.detail === `(package) ${expectedPackage}`);
    if (candidate?.kind !== 9 || candidate.textEdit?.newText !== expectedInsertion) {
      throw new Error(`Missing package completion for ${expectedPackage}: ${JSON.stringify(completions)}`);
    }
    const range = candidate.textEdit.range;
    if (range.start.line !== 0 || range.end.line !== 0 || range.end.character !== importLine.length) {
      throw new Error(`Invalid import completion range: ${JSON.stringify(range)}`);
    }
    const expectedStart = importLine.length - (importPath.split(".").at(-1)?.length ?? 0);
    if (range.start.character !== expectedStart) throw new Error("Import completion did not replace only the current UTF-16 segment");
  }

  const importClassCompletionDurationsMs = [];
  for (const [importPath, expectedType, expectedKind] of [
    ["org.gradle.api.Pro", "org.gradle.api.Project", 8],
    ["java.util.ArrayL", "java.util.ArrayList", 7],
    ["java.util.Map.En", "java.util.Map.Entry", 8],
    ["kotlin.collections.Map.En", "kotlin.collections.Map.Entry", 8],
    ["java.lang.Thread.St", "java.lang.Thread.State", 13],
  ]) {
    const line = `/* 😀 */ import ${importPath}`;
    notify("textDocument/didChange", {
      textDocument: { uri: scriptUri, version: ++version },
      contentChanges: [{ text: `${line}\n${scriptText}\nval broken =` }],
    });
    const startedAt = performance.now();
    const result = await withTimeout(
      request("textDocument/completion", withDocument({ line: 0, character: line.length })),
      30_000,
      `Import class completion for ${importPath}`,
    );
    importClassCompletionDurationsMs.push(Math.round(performance.now() - startedAt));
    const item = result?.items?.find((candidate) => candidate.detail.endsWith(` ${expectedType}`));
    if (item?.kind !== expectedKind || item.textEdit?.newText !== expectedType.split(".").at(-1)) {
      throw new Error(`Missing class completion for ${expectedType}: ${JSON.stringify(result)}`);
    }
  }
  const keywordLine = "/* 😀 */ imp";
  notify("textDocument/didChange", {
    textDocument: { uri: scriptUri, version: ++version },
    contentChanges: [{ text: `${keywordLine}\n${scriptText}` }],
  });
  const keywordCompletion = await withTimeout(
    request("textDocument/completion", withDocument({ line: 0, character: keywordLine.length })),
    30_000,
    "Import keyword completion",
  );
  const keyword = keywordCompletion?.items?.find((item) => item.label === "import");
  if (keyword?.kind !== 14 || keyword.textEdit?.newText !== "import " || keyword.textEdit.range.start.character !== 9) {
    throw new Error(`Invalid import keyword completion: ${JSON.stringify(keywordCompletion)}`);
  }

  const semanticCompletionDurationsMs = [];
  for (const [before, after, expected, kind] of [
    ["dependencies { impl", "ementation\nval broken =\n", "implementation", 3],
    ["tasks.reg", "", "register", 2],
    ["repositories { mav", " }", "mavenCentral", 2],
    ["project.na", "", "name", 10],
    ["val completionLocal = 42\n/* 😀 */ completionL", "ocal", "completionLocal", 10],
    ["fun completionSmart(value: Any) { if (value is String) value.sub", " }", "substring", 3],
    ["fun <T> List<T>.completionFirst(): T = first()\nlistOf(\"hi\").completionF", "", "completionFirst", 2],
  ]) {
    const text = `${scriptText}\n${before}${after}`;
    const prefixLines = `${scriptText}\n${before}`.split("\n");
    notify("textDocument/didChange", {
      textDocument: { uri: scriptUri, version: ++version },
      contentChanges: [{ text }],
    });
    const startedAt = performance.now();
    const result = await withTimeout(
      request("textDocument/completion", withDocument({ line: prefixLines.length - 1, character: prefixLines.at(-1).length })),
      30_000,
      `Semantic completion for ${expected}`,
    );
    semanticCompletionDurationsMs.push(Math.round(performance.now() - startedAt));
    const item = result?.items?.find((candidate) => candidate.label === expected);
    if (item?.kind !== kind || item.textEdit?.newText !== expected || !item.detail) {
      throw new Error(`Missing semantic completion for ${expected}: ${JSON.stringify(result)}`);
    }
    if (expected === "completionFirst" && !item.detail.includes("String")) {
      throw new Error(`Receiver type was not substituted: ${item.detail}`);
    }
    if (result.items.length > 128) throw new Error("Semantic completion exceeded its result bound");
  }

  const processStatus = await readFile(`/proc/${server.pid}/status`, "utf8");
  const currentRssKiB = Number(/^VmRSS:\s+(\d+)\s+kB$/m.exec(processStatus)?.[1]);
  const peakRssKiB = Number(/^VmHWM:\s+(\d+)\s+kB$/m.exec(processStatus)?.[1]);
  const threads = Number(/^Threads:\s+(\d+)$/m.exec(processStatus)?.[1]);
  if (!Number.isFinite(peakRssKiB)) throw new Error("VmHWM is unavailable; this check requires Linux /proc");

  // Optional observation only: these commands do not request GC or alter JVM limits.
  const memoryDetails = process.env.GRADLE_LSP_MEMORY_DETAILS === "1" ? {
    heap: execFileSync("jcmd", [String(server.pid), "GC.heap_info"], { encoding: "utf8", timeout: 10_000 }),
    metaspace: execFileSync("jcmd", [String(server.pid), "VM.metaspace", "basic=true", "scale=KB"], { encoding: "utf8", timeout: 10_000 }),
    codeCache: execFileSync("jcmd", [String(server.pid), "Compiler.codecache"], { encoding: "utf8", timeout: 10_000 }),
    process: await readFile(`/proc/${server.pid}/smaps_rollup`, "utf8"),
  } : undefined;
  console.log(JSON.stringify({
    currentRssKiB,
    peakRssKiB,
    threads,
    maximumRssKiB,
    externalDefinitionDurationsMs,
    externalHoverMs,
    importCompletionDurationsMs,
    importClassCompletionDurationsMs,
    semanticCompletionDurationsMs,
    memoryDetails,
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
