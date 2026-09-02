"use client";

import { ArrowUp, ArrowUpRight } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, KeyboardEvent, MouseEvent, useEffect, useRef, useState } from "react";

import { Button } from "@/components/ardor/button";
import styles from "./next-gen-landing.module.css";

type VisualKind = "tunnel" | "mesh" | "beams" | "wedges";

function CanvasVisual({ kind }: { kind: VisualKind }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (!canvas || !context) return;

    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let width = 0;
    let height = 0;
    let frame = 0;

    const resize = () => {
      const bounds = canvas.getBoundingClientRect();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      width = bounds.width;
      height = bounds.height;
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      context.setTransform(dpr, 0, 0, dpr, 0, 0);
    };

    const tunnel = (time: number) => {
      context.strokeStyle = "#ff5e3a";
      context.lineWidth = 1.15;
      const maxRadius = Math.max(width, height) * 0.9;
      for (let index = 0; index < 36; index += 1) {
        let progress = index / 36;
        if (!reduceMotion) progress = (progress - time * 0.052 + 1) % 1;
        const radius = maxRadius * Math.pow(progress, 1.8);
        const x = width * 0.5 + (width * 0.12 - width * 0.5) * (1 - progress);
        const y = height * 0.38 + (height * 0.84 - height * 0.38) * (1 - progress);
        context.beginPath();
        context.arc(x, y, radius, 0, Math.PI * 2);
        context.stroke();
      }
    };

    const mesh = (time: number) => {
      context.strokeStyle = "rgba(150, 242, 84, 0.58)";
      context.fillStyle = "#96f254";
      context.lineWidth = 0.9;
      const columns = 7;
      const rows = 17;
      const cellWidth = width / (columns - 1);
      const cellHeight = height / 11;
      const points: Array<Array<{ x: number; y: number }>> = [];

      for (let row = -2; row < rows; row += 1) {
        const rowPoints = [];
        for (let column = -1; column <= columns; column += 1) {
          const x = column * cellWidth + (row % 2 === 0 ? 0 : cellWidth * 0.5);
          const wave = reduceMotion ? 0 : Math.sin(column * 0.8 + row * 0.5 + time * 1.35) * 10;
          const y = row * cellHeight + wave;
          rowPoints.push({ x, y });
          if (row > 0 && row < rows - 4 && column >= 0 && column < columns) {
            context.beginPath();
            context.arc(x, y, 2.2, 0, Math.PI * 2);
            context.fill();
          }
        }
        points.push(rowPoints);
      }

      context.beginPath();
      for (let row = 2; row < points.length - 2; row += 1) {
        for (let column = 1; column < points[row].length - 1; column += 1) {
          const point = points[row][column];
          const nextColumn = row % 2 === 0 ? column - 1 : column;
          context.moveTo(point.x, point.y);
          context.lineTo(points[row][column + 1].x, points[row][column + 1].y);
          context.moveTo(point.x, point.y);
          context.lineTo(points[row + 1][nextColumn].x, points[row + 1][nextColumn].y);
          context.moveTo(point.x, point.y);
          context.lineTo(points[row + 1][nextColumn + 1].x, points[row + 1][nextColumn + 1].y);
        }
      }
      context.stroke();
    };

    const beams = (time: number) => {
      context.strokeStyle = "#0c0c0c";
      context.lineWidth = 1.1;
      const originX = width * 1.02;
      const originY = height * 0.52;
      context.beginPath();
      for (let index = 0; index < 28; index += 1) {
        const pulse = reduceMotion ? 0 : Math.sin(time * 0.6 + index * 0.18) * 0.045;
        const angle = Math.PI * 0.58 + Math.PI * 0.84 * (index / 27) + pulse;
        context.moveTo(originX, originY);
        context.lineTo(
          originX + Math.cos(angle) * width * 1.7,
          originY + Math.sin(angle) * width * 1.7,
        );
      }
      context.stroke();
    };

    const wedges = (time: number) => {
      context.fillStyle = "#0c0c0c";
      const centerY = height * 0.59;
      const offsetA = reduceMotion ? 0 : Math.sin(time * 0.7) * 12;
      const offsetB = reduceMotion ? 0 : Math.cos(time * 0.55) * 12;
      context.beginPath();
      context.moveTo(width + offsetA, centerY);
      context.lineTo(width * 0.04 + offsetA, centerY);
      context.lineTo(width + offsetA, centerY - width * 0.38);
      context.fill();
      context.beginPath();
      context.moveTo(width + offsetB, centerY + 42);
      context.lineTo(width * 0.04 + offsetB, centerY + width * 0.42);
      context.lineTo(width + offsetB, centerY + width * 0.42);
      context.fill();
    };

    const render = (milliseconds: number) => {
      context.clearRect(0, 0, width, height);
      const time = milliseconds / 1000;
      if (kind === "tunnel") tunnel(time);
      if (kind === "mesh") mesh(time);
      if (kind === "beams") beams(time);
      if (kind === "wedges") wedges(time);
      if (!reduceMotion) frame = window.requestAnimationFrame(render);
    };

    resize();
    render(0);
    window.addEventListener("resize", resize);
    return () => {
      window.removeEventListener("resize", resize);
      window.cancelAnimationFrame(frame);
    };
  }, [kind]);

  return <canvas ref={canvasRef} aria-hidden="true" className={styles.canvas} />;
}

function PairMark({ tone = "dark" }: { tone?: "dark" | "orange" | "green" }) {
  return (
    <span className={`${styles.pairMark} ${styles[`pairMark_${tone}`]}`} aria-hidden="true">
      <span /><span />
    </span>
  );
}

export function NextGenLanding() {
  const router = useRouter();
  const [demoDraft, setDemoDraft] = useState("");
  const [demoHint, setDemoHint] = useState("");
  const landingRef = useRef<HTMLElement>(null);
  const demoRef = useRef<HTMLElement>(null);

  useEffect(() => {
    let frame = 0;
    const updateReveal = () => {
      frame = 0;
      const section = demoRef.current;
      const landing = landingRef.current;
      if (!section || !landing) return;
      const viewport = window.innerHeight;
      const top = section.getBoundingClientRect().top;
      const progress = Math.max(0, Math.min(1, (viewport * 0.94 - top) / (viewport * 0.94)));
      landing.style.setProperty("--demo-reveal", progress.toFixed(3));
      landing.style.setProperty("--demo-page-opacity", Math.min(1, progress * 1.08).toFixed(3));
      landing.style.setProperty("--demo-page-blur", `${(1 - progress) * 3}rem`);
      landing.style.setProperty("--demo-page-scale", (1.08 - progress * 0.08).toFixed(3));
      landing.style.setProperty("--demo-content-opacity", Math.max(0, Math.min(1, (progress - 0.28) * 2.4)).toFixed(3));
      landing.style.setProperty("--demo-content-shift", `${(1 - progress) * 4}rem`);
      landing.style.setProperty("--demo-content-scale", (0.97 + progress * 0.03).toFixed(3));
    };
    const scheduleReveal = () => {
      if (!frame) frame = window.requestAnimationFrame(updateReveal);
    };
    updateReveal();
    window.addEventListener("scroll", scheduleReveal, { passive: true });
    window.addEventListener("resize", scheduleReveal);
    return () => {
      window.removeEventListener("scroll", scheduleReveal);
      window.removeEventListener("resize", scheduleReveal);
      window.cancelAnimationFrame(frame);
    };
  }, []);

  useEffect(() => {
    const phrases = ["帮我分析这份简历", "为下周面试制定计划", "整理我最近的面试反馈"];
    let phraseIndex = 0;
    let characterIndex = 0;
    let deleting = false;
    let timer = 0;
    const tick = () => {
      const phrase = phrases[phraseIndex];
      characterIndex += deleting ? -1 : 1;
      setDemoHint(phrase.slice(0, characterIndex));
      let delay = deleting ? 38 : 72;
      if (!deleting && characterIndex === phrase.length) { deleting = true; delay = 1300; }
      else if (deleting && characterIndex === 0) { deleting = false; phraseIndex = (phraseIndex + 1) % phrases.length; delay = 280; }
      timer = window.setTimeout(tick, delay);
    };
    timer = window.setTimeout(tick, 420);
    return () => window.clearTimeout(timer);
  }, []);

  function enterAgent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    router.push("/login");
  }

  function handleDemoKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      router.push("/login");
    }
  }

  async function scrollToAgent(event: MouseEvent<HTMLAnchorElement>) {
    event.preventDefault();
    try {
      const response = await fetch("/api/auth/me", { headers: { Accept: "application/json" } });
      if (response.ok) {
        router.push("/app");
        return;
      }
    } catch {
      // The public preview remains usable when the backend is temporarily unavailable.
    }
    window.history.pushState(null, "", "#career-agent");
    demoRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <main ref={landingRef} className={styles.nextGenLanding} id="top">
      <div className={styles.pageColorWash} aria-hidden="true" />
      <section className={styles.heroIntro}>
        <div>
          <h1 className={styles.heroTitle}>
            <span className={styles.heroBuild}>Build a</span>
            <span className={styles.heroCareer}>career</span>
            <span className={styles.heroLockup}>
              <span className={styles.heroSystem}>system</span>
              <span className={styles.heroMemory}>that<br />remembers.</span>
            </span>
          </h1>
        </div>
        <div className={styles.heroAside}>
          <p>不只是一次问答。</p>
          <p>Ardor 把简历、目标、面试练习与后续行动，连接成一套会持续理解你的职业系统。</p>
          <a href="#career-agent" onClick={scrollToAgent}>Ask Ardor. Move forward. <ArrowUpRight aria-hidden /></a>
        </div>
      </section>

      <section className={styles.moduleGrid} id="system" aria-label="Ardor 核心能力">
        <article className={`${styles.module} ${styles.moduleSignal}`}>
          <CanvasVisual kind="tunnel" />
          <header>
            <p className={styles.moduleLabel}>01 / UNDERSTAND</p>
            <h2>Career<br />Signal<br />Clusters.</h2>
          </header>
          <footer><p>从简历和对话中识别能力、经历与偏好，形成一份会持续更新的职业上下文。</p><PairMark tone="orange" /></footer>
        </article>

        <article className={`${styles.module} ${styles.moduleAgent}`}>
          <CanvasVisual kind="mesh" />
          <div className={styles.agentEnding}>
            <div><p className={styles.moduleLabel}>02 / CONVERSE</p><h2>This is<br />Your<br />Career OS</h2></div>
            <div className={styles.agentCopy}><PairMark tone="green" /><p>每段讨论可以独立发生，也会延续你稳定的背景、目标与沟通偏好。</p></div>
          </div>
        </article>

        <article className={`${styles.module} ${styles.modulePractice}`}>
          <CanvasVisual kind="beams" />
          <header>
            <p className={styles.moduleLabel}>03 / PRACTICE</p>
            <h2>Interview<br />Loops.</h2>
            <p className={styles.moduleLead}>围绕目标岗位练习，把每次回答拆成优势、风险与下一轮重点。</p>
          </header>
          <footer className={styles.practiceEnding}><PairMark /><p>Practice → feedback → action.<br />练习之后，结果继续向前。</p></footer>
        </article>

        <article className={`${styles.module} ${styles.moduleAct}`}>
          <CanvasVisual kind="wedges" />
          <header>
            <div className={styles.actMeta}><PairMark /><p className={styles.moduleLabel}>04 / ACT</p></div>
            <h2>04</h2>
            <p className={styles.moduleLead}>简历理解、总体记忆、模拟面试、行动待办。四个模块合成一条可执行的求职闭环。</p>
          </header>
          <footer><p>TURN INSIGHT INTO MOMENTUM.</p><ArrowUpRight aria-hidden /></footer>
        </article>
      </section>

      <section ref={demoRef} className={styles.demoAgent} id="career-agent" aria-labelledby="demo-agent-title">
        <div className={styles.demoStage}>
          <div className={styles.demoAgentInner}>
            <p className={styles.demoEyebrow}>A NEW CONVERSATION</p>
            <h2 id="demo-agent-title">What&apos;s next?</h2>
            <p className={styles.demoLead}>先说出此刻最想推进的事情。登录后，真正的 Ardor 会带着你的职业上下文继续。</p>

            <form className={styles.demoComposer} onSubmit={enterAgent}>
              <textarea
                value={demoDraft}
                onChange={(event) => setDemoDraft(event.target.value)}
                onKeyDown={handleDemoKeyDown}
                rows={3}
                maxLength={50000}
                aria-label="给 Ardor 的消息"
                placeholder={demoHint ? `${demoHint}…` : "问 Ardor 任何职业问题…"}
              />
              <div className={styles.demoComposerFoot}>
                <button type="submit" aria-label="发送并登录">
                  <ArrowUp aria-hidden />
                </button>
              </div>
            </form>

          </div>
        </div>
      </section>

      <section className={styles.callToAction}>
        <div>
          <p className={styles.eyebrow}>READY TO CONNECT THE SIGNALS?</p>
          <h2>让你的下一步，<br />不再从零开始。</h2>
        </div>
        <div className={styles.ctaAside}>
          <p>先完成一次简历理解，再让 Ardor 陪你把目标、模拟面试与行动计划串成可继续的求职闭环。</p>
          <Button asChild variant="ghost" size="lg" className={styles.registerButton}>
            <Link href="/register">开始使用 <ArrowUpRight aria-hidden /></Link>
          </Button>
        </div>
      </section>

      <footer className={styles.siteFooter}>
        <span>ARDOR / AI CAREER AGENT</span>
        <span>SELF-HOSTED · YOUR DATA STAYS YOURS</span>
      </footer>
    </main>
  );
}
