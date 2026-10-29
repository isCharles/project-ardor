"use client";

import { LoaderCircle, SkipForward, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { useLocale } from "@/lib/locale";
import { PcmPlayer, primedAudioContext } from "@/lib/pcm-playback";
import { realtimeUrl } from "@/lib/realtime-url";

export type VoiceInterviewDraft = {
  resumeAnalysisId: string | null;
  targetCompany: string;
  targetRole: string;
  questionCount: number;
};

type Props = {
  payload: VoiceInterviewDraft;
  onReady: (sessionId: string) => void;
  onCancel: () => void;
  onError: (message: string) => void;
};

type Phase = "connecting" | "generating" | "reading";
type RealtimeEvent = {
  type: string;
  text?: string | null;
  audio?: string | null;
  detail?: string | null;
  turn?: string | null;
};

const CREATION_TIMEOUT_MS = 120_000;
const READING_GRACE_MS = 30_000;

/**
 * Creates a voice interview on one connection and plays the first question while the remaining
 * questions are still being written. It hands over to the interview page only after the spoken
 * question has drained, so the answer recorder never competes with the preview audio.
 */
export function VoiceInterviewCreator({ payload, onReady, onCancel, onError }: Props) {
  const { t } = useLocale();
  const tRef = useRef(t);
  const [phase, setPhase] = useState<Phase>("connecting");
  const [skipped, setSkipped] = useState(false);
  const callbacksRef = useRef({ onReady, onCancel, onError });
  const payloadRef = useRef(payload);
  const skippedRef = useRef(false);
  const skipRef = useRef<() => void>(() => undefined);
  const cancelRef = useRef<() => void>(() => undefined);

  useEffect(() => {
    tRef.current = t;
  }, [t]);

  useEffect(() => {
    callbacksRef.current = { onReady, onCancel, onError };
  }, [onReady, onCancel, onError]);

  useEffect(() => {
    const tr = (english: string, chinese: string) => tRef.current(english, chinese);
    let disposed = false;
    let done = false;
    let createdId: string | null = null;
    let audioStarted = false;
    let audioDone = false;
    let currentTurn: string | null = null;
    let creationTimer: number | null = null;
    let graceTimer: number | null = null;
    let readyTimer: number | null = null;
    const player = new PcmPlayer(primedAudioContext());
    const socket = new WebSocket(realtimeUrl("/ws/interviews/voice/new"));

    const showPhase = (next: Phase) => {
      if (!disposed) setPhase(next);
    };

    function clearTimers() {
      if (creationTimer !== null) window.clearTimeout(creationTimer);
      if (graceTimer !== null) window.clearTimeout(graceTimer);
      creationTimer = null;
      graceTimer = null;
    }

    function finish() {
      if (done) return;
      const sessionId = createdId;
      if (!sessionId) return;
      if (!skippedRef.current && audioStarted && !audioDone) return;
      done = true;
      clearTimers();
      const wait = skippedRef.current ? 0 : Math.round(player.pendingSeconds() * 1000) + 250;
      readyTimer = window.setTimeout(() => {
        if (disposed) return;
        try { socket.close(1000, "interview created"); } catch { /* already closed */ }
        callbacksRef.current.onReady(sessionId);
      }, Math.min(wait, 10_000));
    }

    function fail(message: string) {
      if (done) return;
      done = true;
      clearTimers();
      try { socket.close(1011, "creation failed"); } catch { /* already closed */ }
      player.clear();
      if (disposed) return;
      callbacksRef.current.onError(message);
      callbacksRef.current.onCancel();
    }

    function handle(event: RealtimeEvent) {
      if (disposed) return;
      const matchesTurn = !event.turn || event.turn === currentTurn;
      switch (event.type) {
        case "tts_connecting":
          if (event.turn) currentTurn = event.turn;
          if (!skippedRef.current) showPhase("generating");
          break;
        case "questions_generating":
          if (!skippedRef.current) showPhase("generating");
          break;
        case "tts_started":
          if (!matchesTurn) break;
          audioStarted = true;
          if (!skippedRef.current) showPhase("reading");
          break;
        case "audio_delta":
          if (!event.audio || !matchesTurn || skippedRef.current) break;
          audioStarted = true;
          showPhase("reading");
          // Playback failure must not block creation: the question is also shown on the next page.
          void player.play(event.audio).catch(() => undefined);
          break;
        case "tts_done":
          if (!matchesTurn) break;
          audioDone = true;
          if (!createdId) showPhase("generating");
          finish();
          break;
        case "interview_created":
          if (!event.text) break;
          createdId = event.text;
          // The document can be saved before the first question has finished playing, or before a
          // slow synthesis connection has produced any audio at all. Leave a bounded window for it.
          if (!skippedRef.current && !audioDone) {
            if (graceTimer === null) {
              graceTimer = window.setTimeout(() => {
                audioDone = true;
                finish();
              }, READING_GRACE_MS);
            }
          } else {
            finish();
          }
          break;
        case "questions_cancelled":
          fail(tr("Interview preparation was cancelled", "面试准备已取消"));
          break;
        case "error":
          if (event.turn) {
            // Synthesis failed; the interview can still be created and read on screen.
            audioDone = true;
            if (createdId) finish();
            else showPhase("generating");
          } else {
            fail(event.detail || tr("Could not prepare the interview. Try again.", "无法准备面试，请重试"));
          }
          break;
        default:
          break;
      }
    }

    skipRef.current = () => {
      if (done || skippedRef.current) return;
      skippedRef.current = true;
      if (!disposed) setSkipped(true);
      player.clear();
      if (createdId) finish();
      else showPhase("generating");
    };

    cancelRef.current = () => {
      if (done) return;
      done = true;
      clearTimers();
      try { socket.close(1000, "cancelled"); } catch { /* already closed */ }
      player.clear();
      if (!disposed) callbacksRef.current.onCancel();
    };

    socket.onopen = () => {
      if (disposed) return;
      socket.send(JSON.stringify({ type: "create_interview", ...payloadRef.current }));
    };
    socket.onmessage = (message) => {
      try {
        handle(JSON.parse(String(message.data)) as RealtimeEvent);
      } catch {
        fail(tr("Could not read the voice service response", "无法读取语音服务响应"));
      }
    };
    socket.onerror = () => {
      if (createdId) {
        audioDone = true;
        finish();
      } else {
        fail(tr("Live voice connection is unstable. Try again shortly.", "实时语音网络波动，请稍后重试"));
      }
    };
    socket.onclose = () => {
      if (done) return;
      if (createdId) {
        audioDone = true;
        finish();
      } else {
        fail(tr("Live voice disconnected before the interview was ready.", "面试尚未准备好，实时语音连接已断开"));
      }
    };
    creationTimer = window.setTimeout(
      () => fail(tr("Interview preparation timed out. Try again.", "准备面试超时，请重试")),
      CREATION_TIMEOUT_MS);

    return () => {
      disposed = true;
      done = true;
      clearTimers();
      if (readyTimer !== null) window.clearTimeout(readyTimer);
      try { socket.close(1000, "left"); } catch { /* already closed */ }
      player.close();
    };
  }, []);

  const title = phase === "reading"
    ? t("Reading the first question", "正在朗读第一题")
    : phase === "generating"
      ? t("Preparing your interview", "正在准备面试")
      : t("Connecting to live voice", "正在连接实时语音");
  const detail = skipped
    ? t("Skipping the reading. The interview will open once it is saved.", "已跳过朗读，题目保存后会直接进入面试。")
    : phase === "reading"
      ? t("Listen to the question. Answering starts on the next screen.", "请听题，朗读结束后进入作答页面。")
      : t("The first question is read aloud as soon as it is written.", "第一题写好后会立即朗读。");

  return (
    <div className="mt-7" data-voice-interview-creator>
      <p className="text-xs font-medium uppercase tracking-[0.16em] text-primary">{t("Voice interview", "语音面试")}</p>
      <h2 className="mt-2 flex items-center gap-2.5 text-2xl font-semibold">
        <LoaderCircle className="size-5 animate-spin text-violet-600" />
        {title}
      </h2>
      <p className="mt-3 text-sm text-muted-foreground">{detail}</p>
      <div className="mt-6 h-1.5 max-w-sm overflow-hidden rounded-full bg-stone-100">
        <div className="h-full w-2/3 animate-pulse rounded-full bg-gradient-to-r from-violet-500 to-blue-500" />
      </div>
      <div className="mt-6 flex flex-wrap gap-2">
        {!skipped && <Button type="button" variant="outline" onClick={() => skipRef.current()}>
          <SkipForward className="mr-2 size-4" />{t("Skip reading", "跳过朗读")}
        </Button>}
        <Button type="button" variant="outline" onClick={() => cancelRef.current()}>
          <X className="mr-2 size-4" />{t("Cancel", "取消")}
        </Button>
      </div>
    </div>
  );
}
