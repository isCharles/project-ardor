import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function MarkdownContent({ children, className = "" }: { children: string; className?: string }) {
  return <div className={`ardor-markdown ${className}`}><ReactMarkdown remarkPlugins={[remarkGfm]} components={{ img: () => null }}>{children}</ReactMarkdown></div>;
}
