import { Plugin } from "@opencode/plugin";
import { existsSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

// Runs `importtruth check` after Java edits and reports findings back.
// Cold JVM plus first-time indexing can exceed a quarter minute on Windows;
// warm checks return in about a second. Never fail the edit on timeout:
// spawnSync kills the check and we simply report nothing that round.
const CHECK_TIMEOUT_MS = 60000;

function editedFiles(tool: string, args: Record<string, unknown>): string[] {
  if (tool === "edit" || tool === "write") {
    return typeof args.path === "string" ? [args.path] : [];
  }
  if (tool !== "patch" && tool !== "apply_patch") return [];
  const text = typeof args.patchText === "string" ? args.patchText : "";
  const files: string[] = [];
  for (const line of text.split("\n")) {
    const match = line.match(/^\*\*\* (?:Add File|Update File|Delete File|Move to):\s*(.+?)\s*$/);
    if (match) files.push(match[1]);
  }
  return files;
}

function projectRoot(file: string): string {
  let dir = dirname(resolve(file));
  for (let i = 0; i < 12; i++) {
    if (existsSync(join(dir, "pom.xml"))) return dir;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return dirname(resolve(file));
}

function check(jar: string, project: string, file: string): string | null {
  if (!file.endsWith(".java")) return null;
  try {
    if (!existsSync(file) || !statSync(file).isFile()) return null;
  } catch {
    return null;
  }
  const run = spawnSync("java", ["-jar", jar, "check", project, file], {
    timeout: CHECK_TIMEOUT_MS,
    encoding: "utf8",
  });
  const out = typeof run.stdout === "string" ? run.stdout.trim() : "";
  return out === "" ? null : out;
}

export default Plugin.define({
  id: "importtruth",
  async setup(ctx) {
    const jar = process.env.IMPORT_TRUTH_JAR ?? "";
    if (jar === "") return;
    await ctx.tool.hook("execute.after", (event) => {
      const seen = event as unknown as {
        tool: string;
        status: string;
        input?: unknown;
        result?: { content?: unknown };
      };
      if (seen.status !== "completed") return;
      const args = (seen.input ?? {}) as Record<string, unknown>;
      const notes: string[] = [];
      for (const file of editedFiles(seen.tool, args)) {
        const found = check(jar, projectRoot(file), file);
        if (found) notes.push(found);
      }
      if (notes.length === 0 || !seen.result) return;
      const note = "[importtruth]\n" + notes.join("\n");
      const content = seen.result.content;
      if (typeof content === "string") {
        seen.result.content = content + "\n\n" + note;
      } else if (Array.isArray(content)) {
        content.push({ type: "text", text: note });
      } else {
        seen.result.content = note;
      }
    });
  },
});
