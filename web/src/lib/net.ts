/**
 * Wrapper HTTP: di browser pakai fetch (CORS sudah dicek aman),
 * di APK (WebView) pakai CapacitorHttp native — buat endpoint tanpa CORS
 * (mis. RSS detik) dan request yang butuh header bebas.
 */

import { CapacitorHttp, Capacitor } from "@capacitor/core";

export interface NetResponse {
  status: number;
  body: string;
  ok: boolean;
}

export interface NetRequest {
  url: string;
  method?: "GET" | "POST" | "PUT" | "DELETE";
  headers?: Record<string, string>;
  body?: string;
  timeoutMs?: number;
}

export async function httpRequest(req: NetRequest): Promise<NetResponse> {
  if (Capacitor.isNativePlatform()) {
    try {
      const r = await CapacitorHttp.request({
        url: req.url,
        method: req.method ?? "GET",
        headers: req.headers ?? {},
        data: req.body,
        readTimeout: req.timeoutMs ?? 10000,
        connectTimeout: req.timeoutMs ?? 10000,
      });
      const body = typeof r.data === "string" ? r.data : JSON.stringify(r.data ?? "");
      return { status: r.status, body, ok: r.status >= 200 && r.status < 300 };
    } catch {
      return { status: 0, body: "", ok: false };
    }
  }

  try {
    const resp = await fetch(req.url, {
      method: req.method ?? "GET",
      headers: req.headers ?? {},
      body: req.body,
      signal: AbortSignal.timeout(req.timeoutMs ?? 10000),
    });
    return { status: resp.status, body: await resp.text(), ok: resp.ok };
  } catch {
    return { status: 0, body: "", ok: false };
  }
}
