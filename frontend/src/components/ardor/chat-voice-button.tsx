"use client";

import { LoaderCircle, Mic, Square } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

const BAR_COUNT = 14;

/**
 * Chat dictation. The recording is transcribed through the account's existing ASR
 * integration and inserted into the composer; it never auto-sends, so the user can
 * review or correct the text first. While recording, an AnalyserNode drives a small
 * amplitude waveform instead of a static microphone icon.
 */
export function ChatVoiceButton({ disabled, onTranscript, onError }: {
  disabled: boolean;
  onTranscript: (text: string) => void;
  onError: (message: string) => void;
}) {
  const { t } = useLocale();
  const [state, setState] = useState<"idle" | "recording" | "transcribing">("idle");
  const [levels, setLevels] = useState<number[]>(() => Array(BAR_COUNT).fill(0.12));
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const frameRef = useRef<number | null>(null);

  useEffect(() => () => {
    stopMeter();
    if (timerRef.current) clearTimeout(timerRef.current);
    recorderRef.current?.stop();
    stopStream();
  }, []);

  function stopStream() {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }

  function stopMeter() {
    if (frameRef.current != null) cancelAnimationFrame(frameRef.current);
    frameRef.current = null;
    const context = audioContextRef.current;
    audioContextRef.current = null;
    if (context && context.state !== "closed") void context.close().catch(() => undefined);
    setLevels(Array(BAR_COUNT).fill(0.12));
  }

  function startMeter(stream: MediaStream) {
    stopMeter();
    if (typeof AudioContext === "undefined") return;
    try {
      const context = new AudioContext();
      const analyser = context.createAnalyser();
      analyser.fftSize = 64;
      analyser.smoothingTimeConstant = 0.72;
      context.createMediaStreamSource(stream).connect(analyser);
      audioContextRef.current = context;
      const values = new Uint8Array(analyser.frequencyBinCount);
      const tick = () => {
        if (!audioContextRef.current) return;
        analyser.getByteFrequencyData(values);
        const next = Array.from({ length: BAR_COUNT }, (_, index) => {
          const start = Math.floor((index / BAR_COUNT) * values.length);
          const end = Math.max(start + 1, Math.floor(((index + 1) / BAR_COUNT) * values.length));
          let total = 0;
          for (let cursor = start; cursor < end; cursor++) total += values[cursor] ?? 0;
          const value = total / (end - start) / 255;
          return Math.max(0.12, Math.min(1, value * 1.9));
        });
        setLevels(next);
        frameRef.current = requestAnimationFrame(tick);
      };
      tick();
    } catch {
      // Recording still works without the visual meter.
    }
  }

  async function startRecording() {
    onError("");
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      onError(t("Voice input is not supported in this browser.", "当前浏览器不支持语音输入。"));
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
      const mime = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"].find((type) => MediaRecorder.isTypeSupported(type));
      const recorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream);
      streamRef.current = stream;
      recorderRef.current = recorder;
      chunksRef.current = [];
      recorder.ondataavailable = (event) => { if (event.data.size > 0) chunksRef.current.push(event.data); };
      recorder.onstop = () => { void finishRecording(); };
      recorder.start(250);
      startMeter(stream);
      setState("recording");
      timerRef.current = setTimeout(() => stopRecording(), 60_000);
    } catch (reason) {
      stopStream();
      setState("idle");
      onError(reason instanceof DOMException && reason.name === "NotAllowedError"
        ? t("Microphone access is blocked. Allow it in your browser settings.", "没有麦克风权限，请在浏览器地址栏中允许访问。")
        : reason instanceof Error ? reason.message : t("Could not start the microphone.", "无法启动麦克风。"));
    }
  }

  function stopRecording() {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = null;
    const recorder = recorderRef.current;
    if (!recorder || recorder.state === "inactive") return;
    recorder.stop();
    stopMeter();
    setState("transcribing");
  }

  async function finishRecording() {
    stopStream();
    const chunks = chunksRef.current;
    chunksRef.current = [];
    if (chunks.length === 0) {
      setState("idle");
      onError(t("No audio was recorded. Try again.", "没有录到声音，请重试。"));
      return;
    }
    try {
      const form = new FormData();
      form.append("audio", new Blob(chunks, { type: chunks[0].type || "audio/webm" }), "chat-audio.webm");
      const result = await api<{ text: string }>("/api/speech/transcriptions", { method: "POST", body: form });
      if (!result.text?.trim()) {
        onError(t("Speech recognition returned no text. Try again.", "语音识别没有返回文字，请重试。"));
      } else {
        onTranscript(result.text.trim());
      }
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : t("Could not transcribe the recording.", "语音转写失败，请重试。"));
    } finally {
      recorderRef.current = null;
      setState("idle");
    }
  }

  const label = state === "recording" ? t("Stop dictation", "停止听写")
    : state === "transcribing" ? t("Transcribing…", "正在转写…")
      : t("Voice input", "语音输入");
  return <button type="button" disabled={disabled || state === "transcribing"} aria-label={label} title={label} aria-pressed={state === "recording"} onClick={() => state === "recording" ? stopRecording() : void startRecording()} className={`flex h-9 items-center justify-center rounded-full transition disabled:opacity-40 ${state === "recording" ? "min-w-[4.75rem] gap-1 bg-rose-50 px-2 text-rose-600 hover:bg-rose-100" : "size-9 text-stone-500 hover:bg-stone-100"}`}>
    {state === "transcribing" ? <LoaderCircle className="size-4 animate-spin" />
      : state === "recording" ? <>
        <span aria-hidden className="flex h-5 items-center gap-[2px]">{levels.map((level, index) => <span key={index} className="w-[2px] rounded-full bg-rose-500 transition-[height] duration-75 motion-reduce:transition-none" style={{ height: `${Math.max(3, Math.round(level * 18))}px` }} />)}</span>
        <Square className="size-3 shrink-0 fill-current" />
      </> : <Mic className="size-4" />}
  </button>;
}
