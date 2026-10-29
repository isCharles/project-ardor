"use client";

import { FileCode2, Lock, Plus, RefreshCw, Save, Trash2, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { AppDialog } from "@/components/ui/app-dialog";
import { ApiError, api, blobApi } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Project = { id: string; title: string; mainFile: string; currentRevision: number; frozenRevision: number | null; updatedAt: string };
type Revision = { revisionNo: number; sha256: string; message: string | null; createdAt: string };
type Snapshot = { projectId: string; title: string; mainFile: string; revisionNo: number; files: Record<string, string>; sha256: string; frozenRevision: number | null };

const FILE_SUFFIX = /\.(tex|cls|sty|bib|txt|md|cfg)$/i;

export default function LatexWorkspacePage() {
  const { t } = useLocale();
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [selectedFile, setSelectedFile] = useState("main.tex");
  const [revisions, setRevisions] = useState<Revision[]>([]);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState<"" | "create" | "save" | "preview" | "freeze">("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  const [freezeOpen, setFreezeOpen] = useState(false);
  const [restoring, setRestoring] = useState<number | null>(null);
  const [newFile, setNewFile] = useState("");

  const loadProjects = useCallback(async (pick?: string) => {
    try {
      const list = await api<Project[]>("/api/latex/projects");
      setProjects(list);
      setProjectId((current) => pick ?? current ?? list[0]?.id ?? null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not load workspaces", "无法读取 LaTeX 工作区"));
    }
  }, [t]);

  const openProject = useCallback(async (id: string) => {
    setError("");
    setNotice("");
    try {
      const [snapshotResult, historyResult] = await Promise.all([
        api<Snapshot>(`/api/latex/projects/${id}`),
        api<Revision[]>(`/api/latex/projects/${id}/revisions`),
      ]);
      setSnapshot(snapshotResult);
      setDrafts({ ...snapshotResult.files });
      setSelectedFile((current) => (snapshotResult.files[current] != null ? current : snapshotResult.mainFile));
      setRevisions(historyResult);
      setDirty(false);
      setPdfUrl((previous) => { if (previous) URL.revokeObjectURL(previous); return null; });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not open the workspace", "无法打开工作区"));
    }
  }, [t]);

  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void loadProjects(); }, [loadProjects]);
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { if (projectId) void openProject(projectId); }, [projectId, openProject]);

  async function createProject() {
    setBusy("create");
    setError("");
    try {
      const created = await api<Snapshot>("/api/latex/projects", {
        method: "POST",
        body: JSON.stringify({ title: t("My LaTeX resume", "我的 LaTeX 简历") }),
      });
      await loadProjects(created.projectId);
      await openProject(created.projectId);
      setNotice(t("Workspace created from the Chinese template.", "已用中文模板创建 LaTeX 工作区。"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not create a workspace", "创建工作区失败"));
    } finally {
      setBusy("");
    }
  }

  async function save(): Promise<Snapshot | null> {
    if (!snapshot) return null;
    try {
      const saved = await api<Snapshot>(`/api/latex/projects/${snapshot.projectId}/revisions`, {
        method: "POST",
        body: JSON.stringify({ baseRevision: snapshot.revisionNo, files: drafts, message: t("Edited in the workspace", "工作区内手动修改") }),
      });
      setSnapshot(saved);
      setDrafts({ ...saved.files });
      setDirty(false);
      setNotice(t(`Saved revision ${saved.revisionNo}.`, `已保存第 ${saved.revisionNo} 版。`));
      setRevisions(await api<Revision[]>(`/api/latex/projects/${saved.projectId}/revisions`));
      await loadProjects();
      return saved;
    } catch (cause) {
      const conflict = cause instanceof ApiError && cause.status === 409;
      setError(conflict
        ? t("Someone saved a newer revision. Reload before editing.", "工作区已有更新的修订，请先刷新再修改。")
        : cause instanceof Error ? cause.message : t("Could not save", "保存失败"));
      return null;
    }
  }

  async function saveAndPreview() {
    setBusy("save");
    setError("");
    const current = dirty ? await save() : snapshot;
    setBusy("");
    if (!current) return;
    await preview(current);
  }

  async function preview(current: Snapshot) {
    setBusy("preview");
    setError("");
    try {
      const blob = await blobApi(`/latex-api/${current.projectId}?revision=${current.revisionNo}&t=${Date.now()}`);
      setPdfUrl((previous) => { if (previous) URL.revokeObjectURL(previous); return URL.createObjectURL(blob); });
      setNotice(t(`Preview compiled from revision ${current.revisionNo}.`, `已按第 ${current.revisionNo} 版编译预览。`));
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.body.message ?? cause.message : cause instanceof Error ? cause.message : t("Preview failed", "预览编译失败"));
    } finally {
      setBusy("");
    }
  }

  async function freeze() {
    if (!snapshot) return;
    setBusy("freeze");
    try {
      await api<Project>(`/api/latex/projects/${snapshot.projectId}/freeze`, {
        method: "POST",
        body: JSON.stringify({ revisionNo: snapshot.revisionNo }),
      });
      setFreezeOpen(false);
      setNotice(t(`Revision ${snapshot.revisionNo} frozen as the deliverable version.`, `第 ${snapshot.revisionNo} 版已冻结为可投递的正式版本。`));
      await openProject(snapshot.projectId);
      await loadProjects();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not freeze", "冻结失败"));
      setFreezeOpen(false);
    } finally {
      setBusy("");
    }
  }

  async function restore(revisionNo: number) {
    if (!snapshot) return;
    setRestoring(revisionNo);
    setError("");
    try {
      const past = await api<Snapshot>(`/api/latex/projects/${snapshot.projectId}?revision=${revisionNo}`);
      setDrafts({ ...past.files });
      setSelectedFile((current) => (past.files[current] != null ? current : past.mainFile));
      setDirty(true);
      setNotice(t(`Revision ${revisionNo} loaded. Save to create a new revision.`, `已载入第 ${revisionNo} 版，保存后成为新的修订。`));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not load that revision", "无法载入该修订"));
    } finally {
      setRestoring(null);
    }
  }

  function addFile() {
    const name = newFile.trim();
    if (!name) return;
    if (!FILE_SUFFIX.test(name)) {
      setError(t("Supported files: .tex, .cls, .sty, .bib, .txt, .md, .cfg", "仅支持 .tex、.cls、.sty、.bib、.txt、.md、.cfg 文件。"));
      return;
    }
    setError("");
    setDrafts((current) => ({ ...current, [name]: current[name] ?? "" }));
    setSelectedFile(name);
    setNewFile("");
    setDirty(true);
  }

  function removeFile(name: string) {
    if (!snapshot || name === snapshot.mainFile) return;
    setDrafts((current) => {
      const next = { ...current };
      delete next[name];
      return next;
    });
    if (selectedFile === name) setSelectedFile(snapshot.mainFile);
    setDirty(true);
  }

  const frozen = snapshot && snapshot.frozenRevision === snapshot.revisionNo;
  const fileNames = Object.keys(drafts).sort((a, b) => (a === snapshot?.mainFile ? -1 : b === snapshot?.mainFile ? 1 : a.localeCompare(b)));

  return <main className="ardor-workbench min-h-screen text-[#1d1d1f]"><div className="mx-auto max-w-7xl px-5 pb-24 pt-6 md:px-10">
    <header className="flex flex-wrap items-end justify-between gap-5 pb-8 pt-10">
      <div><p className="text-sm font-medium text-violet-600">LATEX</p><h1 className="mt-3 text-4xl font-semibold tracking-[-0.045em] md:text-5xl">{t("LaTeX resume workspace", "LaTeX 简历工作区")}</h1>
        <p className="mt-3 max-w-2xl text-sm leading-6 text-stone-500">{t("Ardor edits these sources through tools; every change is a numbered revision, and only a frozen revision is deliverable.", "Ardor 通过工具修改这里的源文件；每次修改都是可恢复的修订，只有冻结的版本才作为可投递稿。")}</p></div>
      <button onClick={() => void createProject()} disabled={busy !== ""} className="inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm font-medium text-white transition hover:bg-black disabled:opacity-50"><Plus className="size-4" />{t("New workspace", "新建工作区")}</button>
    </header>

    {(error || notice) && <div role={error ? "alert" : "status"} className={`mb-6 rounded-2xl px-5 py-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-blue-50 text-blue-700"}`}>
      <div className="flex items-start justify-between gap-3"><p className="min-w-0 whitespace-pre-wrap break-words font-mono text-xs leading-5">{error || notice}</p><button onClick={() => { setError(""); setNotice(""); }} aria-label={t("Dismiss", "关闭")} className="shrink-0 rounded-full p-1 hover:bg-white/60"><X className="size-4" /></button></div>
    </div>}

    {projects.length === 0 ? <section className="rounded-[2rem] bg-white px-6 py-16 text-center shadow-[0_18px_70px_rgba(0,0,0,0.06)]">
      <FileCode2 className="mx-auto size-9 text-stone-300" />
      <p className="mt-4 text-lg font-medium">{t("No LaTeX workspace yet", "还没有 LaTeX 工作区")}</p>
      <p className="mt-2 text-sm text-stone-500">{t("Start from a Chinese XeLaTeX template, then ask Ardor to adjust it.", "先创建一个中文 XeLaTeX 模板，再让 Ardor 按你的要求修改。")}</p>
    </section> : <div className="grid gap-6 lg:grid-cols-[280px_minmax(0,1fr)]">
      <aside className="space-y-3">
        <section className="rounded-2xl border border-stone-200/70 bg-white/80 p-3">
          <p className="px-2 pb-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Workspaces", "工作区")}</p>
          <div className="space-y-1">{projects.map((project) => <button key={project.id} onClick={() => setProjectId(project.id)} className={`flex w-full items-center justify-between gap-2 rounded-xl px-3 py-2 text-left text-sm transition ${project.id === projectId ? "bg-violet-50 text-violet-700" : "hover:bg-stone-100"}`}>
            <span className="min-w-0 truncate">{project.title}</span>
            <span className="shrink-0 text-[11px] tabular-nums text-stone-400">v{project.currentRevision}{project.frozenRevision != null ? ` · ${t("frozen", "已定稿")} v${project.frozenRevision}` : ""}</span>
          </button>)}</div>
        </section>
        {snapshot && <section className="rounded-2xl border border-stone-200/70 bg-white/80 p-3">
          <div className="flex items-center justify-between px-2 pb-2"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Files", "文件")}</p><span className="text-[11px] tabular-nums text-stone-400">{revisions.length} {t("revisions", "个修订")}</span></div>
          <div className="space-y-1">{fileNames.map((name) => <div key={name} className={`group flex items-center gap-1 rounded-xl ${name === selectedFile ? "bg-stone-100" : ""}`}>
            <button onClick={() => setSelectedFile(name)} className={`min-w-0 flex-1 truncate px-3 py-2 text-left font-mono text-xs ${name === selectedFile ? "text-stone-900" : "text-stone-500"}`}>{name}</button>
            {name !== snapshot.mainFile && <button onClick={() => removeFile(name)} aria-label={t(`Delete ${name}`, `删除 ${name}`)} className="shrink-0 rounded-lg p-1.5 text-stone-300 opacity-0 transition hover:bg-red-50 hover:text-red-600 focus-visible:opacity-100 group-hover:opacity-100"><Trash2 className="size-3.5" /></button>}
          </div>)}</div>
          <form onSubmit={(event) => { event.preventDefault(); addFile(); }} className="mt-2 flex gap-1">
            <input value={newFile} onChange={(event) => setNewFile(event.target.value)} placeholder={t("sections/education.tex", "sections/education.tex")} className="min-w-0 flex-1 rounded-lg border border-stone-200 px-2 py-1.5 font-mono text-xs outline-none focus:border-violet-400" />
            <button type="submit" aria-label={t("Add file", "添加文件")} className="rounded-lg bg-stone-100 px-2 text-stone-600 hover:bg-stone-200"><Plus className="size-3.5" /></button>
          </form>
        </section>}
        {snapshot && <section className="rounded-2xl border border-stone-200/70 bg-white/80 p-3">
          <p className="px-2 pb-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("History", "历史版本")}</p>
          <div className="max-h-64 space-y-1 overflow-auto">{revisions.map((revision) => <div key={revision.revisionNo} className="flex items-center justify-between gap-2 rounded-xl px-2 py-1.5 text-xs hover:bg-stone-50">
            <div className="min-w-0"><p className="tabular-nums text-stone-700">v{revision.revisionNo}{revision.revisionNo === snapshot.revisionNo ? ` · ${t("current", "当前")}` : ""}{revision.revisionNo === snapshot.frozenRevision ? ` · ${t("frozen", "已定稿")}` : ""}</p><p className="truncate text-[11px] text-stone-400">{new Date(revision.createdAt).toLocaleString()}</p></div>
            {revision.revisionNo !== snapshot.revisionNo && <button onClick={() => void restore(revision.revisionNo)} disabled={restoring !== null} className="shrink-0 rounded-lg px-2 py-1 text-[11px] text-violet-600 hover:bg-violet-50 disabled:opacity-40">{restoring === revision.revisionNo ? "…" : t("Load", "载入")}</button>}
          </div>)}</div>
        </section>}
      </aside>

      <div className="space-y-6">
        {snapshot && <section className="rounded-2xl border border-stone-200/70 bg-white/80">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stone-100 px-4 py-3">
            <div className="flex items-center gap-2 text-xs text-stone-500"><span className="font-mono text-stone-800">{selectedFile}</span><span className="tabular-nums">{t("revision", "修订")} {snapshot.revisionNo}</span>{dirty && <span className="rounded-full bg-amber-50 px-2 py-0.5 text-amber-700">{t("unsaved", "未保存")}</span>}{snapshot.frozenRevision != null && <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-emerald-700">{t("frozen", "已定稿")} v{snapshot.frozenRevision}</span>}</div>
            <div className="flex flex-wrap items-center gap-2">
              <button onClick={() => void save()} disabled={busy !== "" || !dirty} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 px-3.5 py-1.5 text-xs text-stone-700 hover:bg-stone-50 disabled:opacity-40"><Save className="size-3.5" />{busy === "save" ? "…" : t("Save", "保存")}</button>
              <button onClick={() => void saveAndPreview()} disabled={busy !== ""} className="inline-flex items-center gap-1.5 rounded-full bg-violet-600 px-3.5 py-1.5 text-xs font-medium text-white hover:bg-violet-700 disabled:opacity-50"><RefreshCw className={`size-3.5 ${busy === "preview" ? "animate-spin" : ""}`} />{busy === "preview" ? t("Compiling…", "编译中…") : t("Preview", "预览 PDF")}</button>
              <button onClick={() => setFreezeOpen(true)} disabled={busy !== "" || dirty || frozen === true} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 px-3.5 py-1.5 text-xs text-stone-700 hover:bg-stone-50 disabled:opacity-40"><Lock className="size-3.5" />{frozen ? t("Frozen", "已冻结") : t("Freeze", "定稿")}</button>
            </div>
          </div>
          <textarea value={drafts[selectedFile] ?? ""} onChange={(event) => { const value = event.target.value; setDrafts((current) => ({ ...current, [selectedFile]: value })); setDirty(true); }} spellCheck={false} aria-label={t("LaTeX source", "LaTeX 源文件")} className="block h-[420px] w-full resize-y rounded-b-2xl bg-[#fbfbfa] p-4 font-mono text-[12.5px] leading-6 text-stone-800 outline-none" />
        </section>}

        {pdfUrl ? <section className="overflow-hidden rounded-2xl border border-stone-200/70 bg-white">
          <div className="flex items-center justify-between border-b border-stone-100 px-4 py-2"><p className="text-xs text-stone-500">{t("Compiled preview", "编译预览")}</p><button onClick={() => { URL.revokeObjectURL(pdfUrl); setPdfUrl(null); }} aria-label={t("Close preview", "关闭预览")} className="rounded-full p-1 text-stone-400 hover:bg-stone-100"><X className="size-4" /></button></div>
          <iframe src={pdfUrl} title={t("Resume PDF preview", "简历 PDF 预览")} className="h-[640px] w-full" />
        </section> : <section className="rounded-2xl border border-dashed border-stone-200 px-6 py-10 text-center text-sm text-stone-400">{t("Preview compiles the saved revision in an isolated directory.", "预览会在隔离目录中编译已保存的修订。")}</section>}
      </div>
    </div>}
  </div>

    {freezeOpen && snapshot && <AppDialog title={t("Freeze this revision", "冻结这个修订")} onClose={() => setFreezeOpen(false)}>
      <p className="text-sm leading-6 text-stone-600">{t(`Freeze revision ${snapshot.revisionNo} as the version you can send out? Later edits stay as drafts.`, `把第 ${snapshot.revisionNo} 版冻结为可以投递的正式版本？之后的修改仍作为草稿修订。`)}</p>
      <div className="mt-6 flex justify-end gap-2">
        <button onClick={() => setFreezeOpen(false)} className="rounded-full px-4 py-2 text-sm text-stone-500 hover:bg-stone-100">{t("Cancel", "取消")}</button>
        <button onClick={() => void freeze()} disabled={busy === "freeze"} className="rounded-full bg-violet-600 px-5 py-2 text-sm font-medium text-white hover:bg-violet-700 disabled:opacity-50">{busy === "freeze" ? "…" : t("Freeze", "冻结")}</button>
      </div>
    </AppDialog>}
  </main>;
}
