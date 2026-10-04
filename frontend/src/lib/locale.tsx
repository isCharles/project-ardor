"use client";

import { createContext, useCallback, useContext, useEffect, useSyncExternalStore } from "react";

export type Locale = "en" | "zh-CN";

type LocaleContextValue = {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (english: string, chinese: string) => string;
};

const STORAGE_KEY = "ardor:locale";
const CHANGE_EVENT = "ardor:locale-change";
const LocaleContext = createContext<LocaleContextValue | null>(null);
let fallbackLocale: Locale = "en";

function readLocale(): Locale {
  try { return window.localStorage.getItem(STORAGE_KEY) === "zh-CN" ? "zh-CN" : "en"; }
  catch { return fallbackLocale; }
}

function subscribe(onChange: () => void) {
  const onStorage = (event: StorageEvent) => { if (event.key === STORAGE_KEY) onChange(); };
  window.addEventListener(CHANGE_EVENT, onChange);
  window.addEventListener("storage", onStorage);
  return () => {
    window.removeEventListener(CHANGE_EVENT, onChange);
    window.removeEventListener("storage", onStorage);
  };
}

export function LocaleProvider({ children }: { children: React.ReactNode }) {
  const locale = useSyncExternalStore(subscribe, readLocale, (): Locale => "en");

  useEffect(() => { document.documentElement.lang = locale; }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    fallbackLocale = next;
    try { window.localStorage.setItem(STORAGE_KEY, next); }
    catch { /* The current tab still switches language. */ }
    window.dispatchEvent(new Event(CHANGE_EVENT));
  }, []);

  const t = useCallback((english: string, chinese: string) => locale === "en" ? english : chinese, [locale]);

  return <LocaleContext.Provider value={{ locale, setLocale, t }}>{children}</LocaleContext.Provider>;
}

export function useLocale() {
  const context = useContext(LocaleContext);
  if (!context) throw new Error("LocaleProvider is missing");
  return context;
}
