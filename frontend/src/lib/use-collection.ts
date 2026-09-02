"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";

/* Every record page does the same three things: load a collection on
   mount, bounce to /login on 401, and reload after a mutation. Each page
   had its own copy, and each copy handled the unauthenticated case
   slightly differently. */
export function useCollection<T>(path: string, failureMessage: string) {
  const router = useRouter();
  const [items, setItems] = useState<T[]>([]);
  const [error, setError] = useState("");

  const handle = useCallback(
    (reason: unknown) => {
      if (reason instanceof ApiError && reason.status === 401) {
        router.replace("/login");
        return;
      }
      setError(reason instanceof Error ? reason.message : failureMessage);
    },
    [router, failureMessage],
  );

  const reload = useCallback(
    () => api<T[]>(path).then(setItems).catch(handle),
    [path, handle],
  );

  useEffect(() => {
    let active = true;
    api<T[]>(path)
      .then((result) => { if (active) setItems(result); })
      .catch((reason) => { if (active) handle(reason); });
    return () => { active = false; };
  }, [path, handle]);

  return { items, setItems, error, setError, reload };
}
