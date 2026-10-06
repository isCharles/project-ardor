import { WorkspaceRail } from "@/components/ardor/workspace-rail";

export default function AppLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <div className="flex h-dvh overflow-hidden bg-white">
      <WorkspaceRail />
      <div className="min-w-0 flex-1 overflow-y-auto">{children}</div>
    </div>
  );
}
