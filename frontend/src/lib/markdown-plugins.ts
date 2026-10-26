import type { Options } from "react-markdown";
import remarkGfm from "remark-gfm";

// A pair of numeric ranges such as 4~16°C and 30~40 must not strike out the text between them.
export const markdownPlugins: NonNullable<Options["remarkPlugins"]> = [[remarkGfm, { singleTilde: false }]];
