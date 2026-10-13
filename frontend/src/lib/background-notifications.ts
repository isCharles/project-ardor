export type BackgroundModule = "resumes" | "recaps" | "knowledge";

export function markBackgroundPending(moduleName: BackgroundModule) {
  void moduleName;
  // Persistent read state comes from the server; this is only a polling wakeup.
  window.dispatchEvent(new Event("ardor:background-refresh"));
}
