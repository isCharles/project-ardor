import ReactMarkdown from "react-markdown";
import { markdownPlugins } from "@/lib/markdown-plugins";

export function MarkdownContent({ children, className = "" }: { children: string; className?: string }) {
  return <div className={`ardor-markdown ${className}`}><ReactMarkdown remarkPlugins={markdownPlugins} components={{ img: () => null }}>{children}</ReactMarkdown></div>;
}
