import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

/** Both routes share one limiter so a burst on one cannot starve the compiler. */
let activeCompilations = 0;
const MAX_COMPILATIONS = 2;
const COMPILE_TIMEOUT_MS = 90_000;
const MAX_SOURCE_CHARS = 2_000_000;

export class LatexBusyError extends Error {
  constructor() {
    super("LaTeX 编译繁忙，请稍后重试");
  }
}

export function latexEngine(): string {
  if (process.env.ARDOR_LATEX_ENGINE) return process.env.ARDOR_LATEX_ENGINE;
  const local = path.resolve(process.cwd(), "..", "preview", "latex-tools", "tectonic.exe");
  return existsSync(local) ? local : "tectonic";
}

/** Workspace paths stay relative and printable; anything else never reaches the compiler. */
export function isSafeWorkspacePath(file: string): boolean {
  return typeof file === "string" && file.length > 0 && file.length <= 200
    && /^[A-Za-z0-9_][A-Za-z0-9_./-]*$/.test(file)
    && !file.split("/").includes("..");
}

function run(command: string, args: string[], cwd: string, restrictReads: boolean): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      env: {
        ...process.env,
        TECTONIC_CACHE_DIR: process.env.ARDOR_TECTONIC_CACHE_DIR
          ?? (existsSync(path.resolve(process.cwd(), "..", "preview", "latex-cache"))
            ? path.resolve(process.cwd(), "..", "preview", "latex-cache")
            : path.join(tmpdir(), "ardor-tectonic-cache")),
        // Web2C engines honour these; they keep a user-authored document from
        // reading or writing outside its temporary directory. Tectonic ignores them.
        ...(restrictReads ? { openin_any: "p", openout_any: "p" } : {}),
      },
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    let timedOut = false;
    const append = (chunk: Buffer) => { output = (output + chunk.toString()).slice(-8000); };
    child.stdout.on("data", append);
    child.stderr.on("data", append);
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, COMPILE_TIMEOUT_MS);
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (timedOut) reject(new Error("LaTeX 编译超时"));
      else if (code === 0) resolve();
      else reject(new Error(output.trim() || `LaTeX 编译失败（退出码 ${code}）`));
    });
  });
}

function isCompletePdf(pdf: Buffer): boolean {
  return pdf.length >= 800 && pdf.subarray(0, 4).toString() === "%PDF"
    && pdf.subarray(-1024).toString("latin1").trimEnd().endsWith("%%EOF");
}

/**
 * Compiles a set of workspace files in an isolated temporary directory.
 * Throws LatexBusyError when both compile slots are taken; every other failure
 * carries the compiler output so the UI can show the real TeX error.
 */
export async function compileLatex(
  files: Record<string, string>,
  mainFile: string,
  options: { restrictReads?: boolean } = {},
): Promise<Buffer> {
  if (!files || typeof files !== "object" || !isSafeWorkspacePath(mainFile) || typeof files[mainFile] !== "string") {
    throw new Error("LaTeX 源文件无效");
  }
  let total = 0;
  for (const [name, content] of Object.entries(files)) {
    if (!isSafeWorkspacePath(name) || typeof content !== "string") throw new Error(`LaTeX 文件路径无效：${name.slice(0, 80)}`);
    total += content.length;
  }
  if (total > MAX_SOURCE_CHARS) throw new Error("LaTeX 源文件过大");

  if (activeCompilations >= MAX_COMPILATIONS) throw new LatexBusyError();
  activeCompilations++;
  let workDir: string | undefined;
  try {
    const scratch = process.env.ARDOR_LATEX_WORK_DIR ?? (process.platform === "win32" ? process.cwd() : tmpdir());
    workDir = await mkdtemp(path.join(scratch, ".latex-report-"));
    const root = path.resolve(workDir);
    for (const [name, content] of Object.entries(files)) {
      const target = path.resolve(root, name);
      if (target !== root && !target.startsWith(root + path.sep)) throw new Error(`LaTeX 文件路径越界：${name}`);
      await mkdir(path.dirname(target), { recursive: true });
      await writeFile(target, content, "utf8");
    }
    const jobname = path.posix.basename(mainFile).replace(/\.tex$/i, "");
    const engine = latexEngine();
    const isXeLatex = /xelatex(?:\.exe)?$/i.test(engine);
    const restrictReads = options.restrictReads === true;
    let compileError: unknown = null;
    try {
      if (isXeLatex) {
        await run(engine, ["-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", mainFile], workDir, restrictReads);
        await run(engine, ["-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", mainFile], workDir, restrictReads);
      } else {
        await run(engine, ["-X", "compile", mainFile], workDir, restrictReads);
      }
    } catch (error) {
      // Tectonic can emit the PDF and then fail while cleaning its temporary
      // cache on Windows. Accept the finished PDF; never accept a partial one.
      compileError = error;
    }
    let pdf: Buffer;
    try {
      pdf = await readFile(path.join(workDir, `${jobname}.pdf`));
    } catch {
      throw compileError ?? new Error(`LaTeX 没有生成 ${jobname}.pdf`);
    }
    if (!isCompletePdf(pdf)) throw compileError ?? new Error("LaTeX 生成的 PDF 不完整");
    if (compileError) {
      // Preserve only the known Windows Tectonic cache-cleanup exception, never
      // accept a timeout or a failed XeLaTeX pass merely because a PDF exists.
      const cleanupOnly = process.platform === "win32" && /tectonic(?:\.exe)?$/i.test(engine)
        && compileError instanceof Error && /Access is denied|os error 5/i.test(compileError.message);
      if (!cleanupOnly) throw compileError;
    }
    return pdf;
  } finally {
    try {
      if (workDir) await rm(workDir, { recursive: true, force: true });
    } finally {
      activeCompilations--;
    }
  }
}
