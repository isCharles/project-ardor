import { WorkspaceRail } from "@/components/ardor/workspace-rail";
import { DomainNavigation } from "@/components/ardor/domain-navigation";

export default function AppLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <div className="flex h-full overflow-hidden bg-white">
      <WorkspaceRail />
      <div data-app-scroll className="min-w-0 flex-1 overflow-y-auto"><DomainNavigation />{children}</div>
    </div>
  );
}
