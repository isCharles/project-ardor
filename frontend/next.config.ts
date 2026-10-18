import type { NextConfig } from "next";

const backendUrl = (process.env.ARDOR_BACKEND_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");

const nextConfig: NextConfig = {
  // Next blocks dev-only assets when the requesting origin is not the server
  // hostname (localhost by default). The desktop client defaults to
  // http://127.0.0.1:3000, so allow that origin in development too.
  allowedDevOrigins: ["127.0.0.1"],
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${backendUrl}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
