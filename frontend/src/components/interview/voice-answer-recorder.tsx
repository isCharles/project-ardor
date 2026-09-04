"use client";

import { LoaderCircle, Mic, Square, Volume2, Waves } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";

type Props = {
  sessionId: string;
  questionId: string;
  disabled: boolean;
  onTranscript: (text: string) => void;
  onError: (message: string) => void;
};

type VoiceState = "idle" | "connecting" | "speaking" | "listening" | "processing";
type RealtimeEvent = { type: string; text?: string | null; audio?: string | null; detail?: string | null };

const INPUT_SAMPLE_RATE = 16_000;
const OUTPUT_SAMPLE_RATE = 24_000;
const MAX_RECORDING_MS = 5 * 60 * 1000;
const LOCAL_VAD_THRESHOLD = 0.035;

export function VoiceAnswerRecorder({ sessionId, questionId, disabled, onTranscript, onError }: Props) {
  const socketRef = useRef<WebSocket | null>(null);
  const socketPromiseRef = useRef<Promise<WebSocket> | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const captureContextRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const playbackContextRef = useRef<AudioContext | null>(null);
  const playbackSourcesRef = useRef(new Set<AudioBufferSourceNode>());
  const nextPlaybackAtRef = useRef(0);
  const stopTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stateRef = useRef<VoiceState>("idle");
  const speechFramesRef = useRef(0);
  const interruptedRef = useRef(false);
  const mountedRef = useRef(true);
  const [state, setState] = useState<VoiceState>("idle");
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [status, setStatus] = useState("实时语音已就绪");

  useEffect(() => {
    if (state !== "listening" && state !== "speaking") return;
    const timer = window.setInterval(() => setElapsedSeconds((seconds) => seconds + 1), 1_000);
    return () => window.clearInterval(timer);
  }, [state]);

  useEffect(() => () => {
    mountedRef.current = false;
    stopCapture();
    clearPlayback();
    socketRef.current?.close(1000, "page left");
    socketRef.current = null;
    void playbackContextRef.current?.close();
    playbackContextRef.current = null;
  }, []);

  function updateState(next: VoiceState) {
    stateRef.current = next;
    if (mountedRef.current) setState(next);
  }

  async function ensureSocket() {
    if (socketRef.current?.readyState === WebSocket.OPEN) return socketRef.current;
    if (socketPromiseRef.current) return socketPromiseRef.current;
    updateState("connecting");
    setStatus("正在连接实时语音…");
    socketPromiseRef.current = new Promise<WebSocket>((resolve, reject) => {
      const socket = new WebSocket(realtimeUrl(sessionId));
      const timeout = window.setTimeout(() => {
        socket.close();
        reject(new Error("实时语音连接超时"));
      }, 12_000);
      socket.onopen = () => {
        window.clearTimeout(timeout);
        socketRef.current = socket;
        socketPromiseRef.current = null;
        socket.onmessage = (event) => {
          try {
            handleServerEvent(JSON.parse(String(event.data)) as RealtimeEvent);
          } catch {
            onError("实时语音响应无法解析，请重新连接");
          }
        };
        socket.onclose = () => {
          socketRef.current = null;
          socketPromiseRef.current = null;
          if (mountedRef.current && stateRef.current !== "idle") {
            stopCapture();
            clearPlayback();
            updateState("idle");
            onError("实时语音连接已断开，请重新开始回答");
          }
        };
        socket.onerror = () => onError("实时语音网络波动，请稍后重试");
        resolve(socket);
      };
      socket.onerror = () => {
        window.clearTimeout(timeout);
        socketPromiseRef.current = null;
        reject(new Error("无法连接实时语音服务"));
      };
    });
    return socketPromiseRef.current;
  }

  function handleServerEvent(event: RealtimeEvent) {
    switch (event.type) {
      case "ready": setStatus("实时语音已就绪"); break;
      case "asr_connecting": setStatus("正在连接实时识别…"); break;
      case "asr_ready":
        setStatus(stateRef.current === "speaking" ? "朗读中，可直接开口打断" : "正在听你回答…");
        break;
      case "tts_connecting": setStatus("正在生成题目语音…"); break;
      case "tts_started":
        updateState("speaking");
        setStatus("朗读中，可直接开口打断");
        break;
      case "audio_delta":
        if (event.audio && !interruptedRef.current) playPcm(event.audio);
        break;
      case "tts_done":
        if (stateRef.current === "speaking") updateState("listening");
        setStatus("正在听你回答，停顿后自动转写");
        break;
      case "tts_interrupted":
        clearPlayback();
        updateState("listening");
        setStatus("已停止朗读，正在听你回答…");
        break;
      case "speech_started":
        interruptQuestion();
        updateState("listening");
        setStatus("正在识别…");
        break;
      case "speech_stopped":
        stopCapture();
        updateState("processing");
        setStatus("检测到停顿，正在整理文字…");
        break;
      case "transcript_delta":
        if (event.text) onTranscript(event.text);
        break;
      case "transcript_final":
        stopCapture();
        if (event.text) onTranscript(event.text);
        updateState("idle");
        setStatus("已转成文字，可以修改后提交");
        break;
      case "error":
        stopCapture();
        clearPlayback();
        updateState("idle");
        onError(event.detail || "实时语音处理失败，请重试");
        break;
    }
  }

  async function startAnswer(readQuestion: boolean) {
    onError("");
    if (!navigator.mediaDevices?.getUserMedia || typeof AudioContext === "undefined") {
      onError("当前浏览器不支持实时语音，请改用文字作答");
      return;
    }
    try {
      const socket = await ensureSocket();
      interruptedRef.current = false;
      setElapsedSeconds(0);
      socket.send(JSON.stringify({ type: "start_asr" }));
      await startCapture(socket);
      if (readQuestion) {
        await ensurePlaybackContext();
        updateState("speaking");
        setStatus("正在生成题目语音…");
        socket.send(JSON.stringify({ type: "speak_question", questionId }));
      } else {
        updateState("listening");
        setStatus("正在听你回答，停顿后自动转写");
      }
      stopTimerRef.current = setTimeout(() => finishAnswer(), MAX_RECORDING_MS);
    } catch (reason) {
      stopCapture();
      clearPlayback();
      updateState("idle");
      onError(reason instanceof DOMException && reason.name === "NotAllowedError"
        ? "没有麦克风权限，请在浏览器地址栏中允许访问"
        : reason instanceof Error ? reason.message : "无法启动实时语音");
    }
  }

  async function startCapture(socket: WebSocket) {
    stopCapture();
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
    });
    const context = new AudioContext();
    await context.resume();
    const source = context.createMediaStreamSource(stream);
    const processor = context.createScriptProcessor(2_048, 1, 1);
    const silent = context.createGain();
    silent.gain.value = 0;
    speechFramesRef.current = 0;
    processor.onaudioprocess = (audioEvent) => {
      if (socket.readyState !== WebSocket.OPEN) return;
      const input = audioEvent.inputBuffer.getChannelData(0);
      detectLocalSpeech(input, socket);
      const pcm = downsampleToPcm16(input, context.sampleRate, INPUT_SAMPLE_RATE);
      socket.send(JSON.stringify({ type: "audio", audio: pcmToBase64(pcm) }));
    };
    source.connect(processor);
    processor.connect(silent);
    silent.connect(context.destination);
    streamRef.current = stream;
    captureContextRef.current = context;
    processorRef.current = processor;
  }

  function detectLocalSpeech(input: Float32Array, socket: WebSocket) {
    if (stateRef.current !== "speaking" || interruptedRef.current) return;
    let sum = 0;
    for (const sample of input) sum += sample * sample;
    const rms = Math.sqrt(sum / input.length);
    speechFramesRef.current = rms >= LOCAL_VAD_THRESHOLD ? speechFramesRef.current + 1 : 0;
    if (speechFramesRef.current < 2) return;
    interruptedRef.current = true;
    clearPlayback();
    socket.send(JSON.stringify({ type: "interrupt_tts" }));
    updateState("listening");
    setStatus("检测到你开口，已停止朗读");
  }

  function finishAnswer() {
    if (stopTimerRef.current) clearTimeout(stopTimerRef.current);
    stopTimerRef.current = null;
    stopCapture();
    clearPlayback();
    if (socketRef.current?.readyState === WebSocket.OPEN) {
      socketRef.current.send(JSON.stringify({ type: "finish_asr" }));
      socketRef.current.send(JSON.stringify({ type: "interrupt_tts" }));
    }
    updateState("processing");
    setStatus("正在完成转写…");
  }

  function interruptQuestion() {
    if (interruptedRef.current) return;
    interruptedRef.current = true;
    clearPlayback();
    if (socketRef.current?.readyState === WebSocket.OPEN) {
      socketRef.current.send(JSON.stringify({ type: "interrupt_tts" }));
    }
  }

  function stopCapture() {
    if (stopTimerRef.current) clearTimeout(stopTimerRef.current);
    stopTimerRef.current = null;
    if (processorRef.current) {
      processorRef.current.onaudioprocess = null;
      processorRef.current.disconnect();
      processorRef.current = null;
    }
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    void captureContextRef.current?.close();
    captureContextRef.current = null;
  }

  async function ensurePlaybackContext() {
    if (!playbackContextRef.current || playbackContextRef.current.state === "closed") {
      playbackContextRef.current = new AudioContext({ sampleRate: OUTPUT_SAMPLE_RATE });
    }
    await playbackContextRef.current.resume();
    return playbackContextRef.current;
  }

  function playPcm(base64: string) {
    void ensurePlaybackContext().then((context) => {
      const bytes = base64ToBytes(base64);
      const samples = new Int16Array(bytes.buffer, bytes.byteOffset, Math.floor(bytes.byteLength / 2));
      const buffer = context.createBuffer(1, samples.length, OUTPUT_SAMPLE_RATE);
      const channel = buffer.getChannelData(0);
      for (let index = 0; index < samples.length; index += 1) channel[index] = samples[index] / 32_768;
      const source = context.createBufferSource();
      source.buffer = buffer;
      source.connect(context.destination);
      source.onended = () => playbackSourcesRef.current.delete(source);
      playbackSourcesRef.current.add(source);
      const startsAt = Math.max(context.currentTime + 0.02, nextPlaybackAtRef.current);
      source.start(startsAt);
      nextPlaybackAtRef.current = startsAt + buffer.duration;
    }).catch(() => onError("题目语音播放失败，请直接阅读题目"));
  }

  function clearPlayback() {
    for (const source of playbackSourcesRef.current) {
      try { source.stop(); } catch { /* source already ended */ }
    }
    playbackSourcesRef.current.clear();
    nextPlaybackAtRef.current = 0;
  }

  const active = state === "speaking" || state === "listening";
  const locked = disabled || state === "connecting" || state === "processing";
  return (
    <div className="rounded-2xl border border-violet-200/70 bg-white/70 p-4 shadow-sm backdrop-blur">
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="outline" disabled={locked || active} onClick={() => startAnswer(true)}>
          {state === "connecting" ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Volume2 className="mr-2 size-4" />}
          朗读并开始
        </Button>
        {active ? (
          <Button type="button" onClick={finishAnswer} className="bg-red-600 hover:bg-red-700">
            <Square className="mr-2 size-4 fill-current" />结束 · {formatTime(elapsedSeconds)}
          </Button>
        ) : (
          <Button type="button" disabled={locked} onClick={() => startAnswer(false)}>
            {state === "processing" ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Mic className="mr-2 size-4" />}
            {state === "processing" ? "正在转写…" : "直接回答"}
          </Button>
        )}
      </div>
      <div className="mt-3 flex items-center gap-2 text-xs text-muted-foreground">
        <Waves className={`size-3.5 ${active ? "animate-pulse text-violet-600" : ""}`} />
        <span>{status}</span>
      </div>
    </div>
  );
}

function realtimeUrl(sessionId: string) {
  const configured = process.env.NEXT_PUBLIC_ARDOR_WS_URL?.replace(/\/$/, "");
  if (configured) return `${configured}/ws/interviews/${sessionId}/voice`;
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const port = window.location.port === "3000" ? ":8080" : window.location.port ? `:${window.location.port}` : "";
  return `${protocol}//${window.location.hostname}${port}/ws/interviews/${sessionId}/voice`;
}

function downsampleToPcm16(input: Float32Array, inputRate: number, outputRate: number) {
  if (inputRate === outputRate) return floatToPcm16(input);
  const ratio = inputRate / outputRate;
  const length = Math.max(1, Math.floor(input.length / ratio));
  const output = new Float32Array(length);
  for (let index = 0; index < length; index += 1) {
    const start = Math.floor(index * ratio);
    const end = Math.min(input.length, Math.floor((index + 1) * ratio));
    let total = 0;
    for (let source = start; source < end; source += 1) total += input[source];
    output[index] = total / Math.max(1, end - start);
  }
  return floatToPcm16(output);
}

function floatToPcm16(input: Float32Array) {
  const pcm = new Int16Array(input.length);
  for (let index = 0; index < input.length; index += 1) {
    const sample = Math.max(-1, Math.min(1, input[index]));
    pcm[index] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
  }
  return pcm;
}

function pcmToBase64(pcm: Int16Array) {
  const bytes = new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength);
  let binary = "";
  for (let index = 0; index < bytes.length; index += 1) binary += String.fromCharCode(bytes[index]);
  return window.btoa(binary);
}

function base64ToBytes(value: string) {
  const binary = window.atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function formatTime(seconds: number) {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, "0");
  const remainder = (seconds % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}
