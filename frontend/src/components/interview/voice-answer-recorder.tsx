"use client";

import { LoaderCircle, Mic, Square, Volume2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { api, blobApi } from "@/lib/api";

type Props = {
  sessionId: string;
  questionId: string;
  disabled: boolean;
  onTranscript: (text: string) => void;
  onError: (message: string) => void;
};

type Transcription = { text: string };

const MAX_RECORDING_MS = 5 * 60 * 1000;

export function VoiceAnswerRecorder({
  sessionId,
  questionId,
  disabled,
  onTranscript,
  onError,
}: Props) {
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const stopTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const audioUrlRef = useRef<string | null>(null);
  const [state, setState] = useState<"idle" | "recording" | "transcribing" | "speaking">("idle");
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  useEffect(() => {
    if (state !== "recording") return;
    const timer = window.setInterval(() => {
      setElapsedSeconds((seconds) => seconds + 1);
    }, 1_000);
    return () => window.clearInterval(timer);
  }, [state]);

  useEffect(() => () => {
    if (recorderRef.current?.state === "recording") {
      recorderRef.current.onstop = null;
      recorderRef.current.stop();
    }
    streamRef.current?.getTracks().forEach((track) => track.stop());
    audioRef.current?.pause();
    if (audioUrlRef.current) URL.revokeObjectURL(audioUrlRef.current);
    if (stopTimerRef.current) clearTimeout(stopTimerRef.current);
  }, []);

  async function startRecording() {
    onError("");
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      onError("当前浏览器不支持录音，请改用文字作答");
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      const mimeType = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"]
        .find((candidate) => MediaRecorder.isTypeSupported(candidate));
      const recorder = new MediaRecorder(stream, {
        ...(mimeType ? { mimeType } : {}),
        audioBitsPerSecond: 32_000,
      });
      streamRef.current = stream;
      recorderRef.current = recorder;
      chunksRef.current = [];
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onerror = () => {
        stopTracks();
        setState("idle");
        onError("录音失败，请检查麦克风权限后重试");
      };
      recorder.onstop = () => void transcribeRecording(recorder.mimeType || mimeType || "audio/webm");
      setElapsedSeconds(0);
      setState("recording");
      recorder.start(1_000);
      stopTimerRef.current = setTimeout(() => recorder.stop(), MAX_RECORDING_MS);
    } catch (reason) {
      setState("idle");
      onError(reason instanceof DOMException && reason.name === "NotAllowedError"
        ? "没有麦克风权限，请在浏览器地址栏中允许访问"
        : "无法启动麦克风，请改用文字作答");
    }
  }

  function stopRecording() {
    if (recorderRef.current?.state === "recording") recorderRef.current.stop();
  }

  async function transcribeRecording(mimeType: string) {
    stopTracks();
    if (stopTimerRef.current) clearTimeout(stopTimerRef.current);
    const blob = new Blob(chunksRef.current, { type: mimeType });
    chunksRef.current = [];
    if (blob.size === 0) {
      setState("idle");
      onError("没有录到声音，请重新作答");
      return;
    }
    setState("transcribing");
    const extension = mimeType.includes("mp4") ? "m4a" : "webm";
    const form = new FormData();
    form.append("audio", new File([blob], `answer.${extension}`, { type: mimeType }));
    try {
      const result = await api<Transcription>(`/api/interviews/${sessionId}/voice/transcriptions`, {
        method: "POST",
        body: form,
      });
      onTranscript(result.text);
    } catch (reason) {
      onError(reason instanceof Error ? reason.message : "语音识别失败，请重试或改用文字作答");
    } finally {
      setState("idle");
    }
  }

  async function playQuestion() {
    onError("");
    audioRef.current?.pause();
    if (audioUrlRef.current) URL.revokeObjectURL(audioUrlRef.current);
    setState("speaking");
    try {
      const blob = await blobApi(
        `/api/interviews/${sessionId}/voice/questions/${questionId}/speech`,
        { method: "POST" },
      );
      const url = URL.createObjectURL(blob);
      audioUrlRef.current = url;
      const audio = new Audio(url);
      audioRef.current = audio;
      audio.onended = () => setState("idle");
      audio.onerror = () => {
        setState("idle");
        onError("题目语音播放失败，请直接阅读题目");
      };
      await audio.play();
    } catch (reason) {
      setState("idle");
      onError(reason instanceof Error ? reason.message : "语音合成失败，请直接阅读题目");
    }
  }

  function stopTracks() {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }

  const locked = disabled || state === "transcribing" || state === "speaking";
  return (
    <div className="rounded-2xl border border-violet-200/70 bg-white/70 p-4 shadow-sm backdrop-blur">
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="outline" disabled={locked || state === "recording"} onClick={playQuestion}>
          {state === "speaking" ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Volume2 className="mr-2 size-4" />}
          播放题目
        </Button>
        {state === "recording" ? (
          <Button type="button" onClick={stopRecording} className="bg-red-600 hover:bg-red-700">
            <Square className="mr-2 size-4 fill-current" />结束录音 · {formatTime(elapsedSeconds)}
          </Button>
        ) : (
          <Button type="button" disabled={locked} onClick={startRecording}>
            {state === "transcribing" ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Mic className="mr-2 size-4" />}
            {state === "transcribing" ? "正在转成文字…" : "开始回答"}
          </Button>
        )}
      </div>
      <p className="mt-3 text-xs text-muted-foreground">录完会先转成文字，你可以修改后再提交。</p>
    </div>
  );
}

function formatTime(seconds: number) {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, "0");
  const remainder = (seconds % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}
