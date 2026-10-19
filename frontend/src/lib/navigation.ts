import catalog from "./navigation-catalog.json";

export type NavigationDestination = {
  id: string; domain: string | null; href: string; legacyHref: string;
  en: string; zh: string; icon: string; aliases: string[]; modules: string[];
  legacySelectable?: boolean; tab?: boolean; section?: string;
};

export const destinations: NavigationDestination[] = catalog.destinations;
export const defaultDestinationIds: string[] = catalog.defaults;

export function normalizeDestinationIds(value: unknown): string[] {
  if (!Array.isArray(value) || value.length > destinations.length
      || value.some(id => typeof id !== "string" || !destinations.some(item => item.id === id))) {
    throw new Error("Invalid sidebar destinations");
  }
  return [...new Set(["chat", ...value])];
}

export function destinationForPath(value: string): NavigationDestination | undefined {
  const pathname = value.split(/[?#]/)[0];
  const matches = (path: string) => pathname === path || (path !== "/app" && pathname.startsWith(`${path}/`));
  return destinations.filter(item => matches(item.href) || item.aliases.some(matches))
    .sort((a, b) => Math.max(b.href.length, ...b.aliases.map(path => path.length))
      - Math.max(a.href.length, ...a.aliases.map(path => path.length)))[0];
}

export function destinationActive(id: string, pathname: string): boolean {
  const current = destinationForPath(pathname);
  if (id === "job-search" || id === "learning") return current?.domain === id;
  return current?.id === id || current?.section === id;
}

/** URL compatibility does not infer or modify an Agent capability. */
export function destinationsFromLegacy(value: unknown): string[] {
  if (!Array.isArray(value)) return ["chat"];
  return [...new Set(["chat", ...value.flatMap(href => {
    const destination = destinations.find(item => item.legacySelectable !== false && item.legacyHref === href);
    return destination ? [destination.id] : [];
  })])];
}

export function legacyHrefsForDestinations(ids: string[]): string[] {
  return [...new Set(normalizeDestinationIds(ids).map(id => destinations.find(item => item.id === id)!.legacyHref))];
}

export function destinationHref(id: string): string {
  return destinations.find(item => item.id === id)?.href ?? "/app";
}
