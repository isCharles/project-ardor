"use client";

const SAMPLE_RATE = 24_000;

let primedContext: AudioContext | null = null;

/** Creates and resumes the playback context inside the user gesture that starts voice. */
export function primeAudioPlayback(): void {
  if (typeof window === "undefined" || typeof AudioContext === "undefined") return;
  if (!primedContext || primedContext.state === "closed") {
    primedContext = new AudioContext({ sampleRate: SAMPLE_RATE });
  }
  void primedContext.resume().catch(() => undefined);
}

export function primedAudioContext(): AudioContext | null {
  return primedContext && primedContext.state !== "closed" ? primedContext : null;
}

/** Plays 24 kHz PCM chunks back to back and reports how much queued audio is left. */
export class PcmPlayer {
  private context: AudioContext | null;
  private readonly sources = new Set<AudioBufferSourceNode>();
  private nextAt = 0;
  private closed = false;

  constructor(context: AudioContext | null = null) {
    this.context = context;
  }

  async resume(): Promise<AudioContext | null> {
    if (this.closed) return null;
    if (!this.context || this.context.state === "closed") {
      this.context = new AudioContext({ sampleRate: SAMPLE_RATE });
    }
    if (this.context.state === "suspended") await this.context.resume();
    return this.closed ? null : this.context;
  }

  async play(base64: string): Promise<void> {
    const context = await this.resume();
    if (!context || this.closed) return;
    const bytes = base64ToBytes(base64);
    const samples = new Int16Array(bytes.buffer, bytes.byteOffset, Math.floor(bytes.byteLength / 2));
    if (samples.length === 0) return;
    const buffer = context.createBuffer(1, samples.length, SAMPLE_RATE);
    const channel = buffer.getChannelData(0);
    for (let index = 0; index < samples.length; index += 1) channel[index] = samples[index] / 32_768;
    const source = context.createBufferSource();
    source.buffer = buffer;
    source.connect(context.destination);
    source.onended = () => this.sources.delete(source);
    this.sources.add(source);
    const startsAt = Math.max(context.currentTime + 0.02, this.nextAt);
    source.start(startsAt);
    this.nextAt = startsAt + buffer.duration;
  }

  clear(): void {
    for (const source of this.sources) {
      try { source.stop(); } catch { /* source already ended */ }
    }
    this.sources.clear();
    this.nextAt = 0;
  }

  pendingSeconds(): number {
    if (!this.context) return 0;
    return Math.max(0, this.nextAt - this.context.currentTime);
  }

  close(): void {
    this.closed = true;
    this.clear();
    const context = this.context;
    this.context = null;
    if (context) void context.close().catch(() => undefined);
  }
}

function base64ToBytes(value: string) {
  const binary = window.atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}
