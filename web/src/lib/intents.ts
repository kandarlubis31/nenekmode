/**
 * IntentMatcher: pola + prioritas + mode respons (first/random/sequential).
 * Port langsung IntentMatcher.java; intents.json di-import dari src/data.
 */

import intentsJson from "../data/intents.json";

interface IntentData {
  tag: string;
  patterns: string[];
  responses: string[];
  response_mode: string;
  priority: number;
  action: string;
}

export interface MatchResult {
  tag: string;
  response: string;
  action: string;
}

interface IntentsFile {
  intents: Array<{
    tag: string;
    patterns?: string[];
    responses?: string[];
    response_mode?: string;
    priority?: number;
    action?: string;
  }>;
}

export class IntentMatcher {
  private readonly intents: IntentData[] = [];
  private readonly sequentialStates = new Map<string, number>();
  readonly jsonString: string;

  constructor() {
    this.jsonString = JSON.stringify(intentsJson, null, 2);
    try {
      const parsed = intentsJson as unknown as IntentsFile;
      for (const obj of parsed.intents ?? []) {
        const intent: IntentData = {
          tag: obj.tag,
          patterns: (obj.patterns ?? []).map((p) => p.toLowerCase()),
          responses: obj.responses ?? [],
          response_mode: obj.response_mode ?? "first",
          priority: obj.priority ?? 0,
          action: obj.action ?? "",
        };
        this.intents.push(intent);
        this.sequentialStates.set(intent.tag, 0);
      }
    } catch {
      /* JSON rusak: matcher kosong, fallback "unknown" */
    }
  }

  getJsonString(): string {
    return this.jsonString;
  }

  match(input: string): MatchResult {
    const lowerInput = input.toLowerCase();
    let bestMatch: IntentData | null = null;

    for (const intent of this.intents) {
      const isMatch = intent.patterns.some((p) => lowerInput.includes(p));
      if (isMatch && (bestMatch === null || intent.priority > bestMatch.priority)) {
        bestMatch = intent;
      }
    }

    if (bestMatch === null) bestMatch = this.getFallbackIntent();

    if (bestMatch === null) {
      return { tag: "unknown", response: "", action: "" };
    }

    return {
      tag: bestMatch.tag,
      action: bestMatch.action,
      response: this.selectResponse(bestMatch),
    };
  }

  private getFallbackIntent(): IntentData | null {
    return this.intents.find((i) => i.tag === "fallback") ?? null;
  }

  private selectResponse(intent: IntentData): string {
    const size = intent.responses.length;
    if (size === 0) return "";

    if (intent.response_mode === "first") {
      return intent.responses[0];
    }
    if (intent.response_mode === "random") {
      return intent.responses[Math.floor(Math.random() * size)];
    }
    if (intent.response_mode === "sequential") {
      const current = this.sequentialStates.get(intent.tag) ?? 0;
      const response = intent.responses[current];
      this.sequentialStates.set(intent.tag, (current + 1) % size);
      return response;
    }
    return intent.responses[0];
  }
}
