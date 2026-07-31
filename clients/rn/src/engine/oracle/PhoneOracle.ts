/**
 * Lightweight Oracle for phone — runs local predictions on Study data.
 * TypeScript port matching KMP's PhoneOracle.kt.
 *
 * Also receives server predictions via Between.
 */
import type { StudyStore } from '../study/StudyStore';
import type { StudyItem } from '../study/StudyItem';
import type { BetweenClient } from '../between/BetweenClient';
import { TtmPhoneForecaster, MODEL_ASSET_PATH } from './ChronosPhoneForecaster';

export interface PhonePrediction {
  text: string;
  category: string;
  confidence: number;
  textKey?: string;
  textParams?: Record<string, string>;
  actionable?: boolean;
}

export class PhoneOracle {
  private cachedPredictions: PhonePrediction[] = [];
  private serverPredictions: PhonePrediction[] = [];
  private ttm: TtmPhoneForecaster;

  constructor(
    private readonly store: StudyStore,
    private readonly deviceId: string,
    private readonly userDid: string,
    modelPath: string = MODEL_ASSET_PATH,
  ) {
    this.ttm = new TtmPhoneForecaster(modelPath);
  }

  /** Load the ONNX model. Call once at startup; returns false if unavailable. */
  async loadModel(): Promise<boolean> {
    return this.ttm.load();
  }

  /** Run local analysis during phone Forge sleep. */
  async analyze(): Promise<PhonePrediction[]> {
    const predictions: PhonePrediction[] = [];
    const entries = await this.store.recentJournal(this.userDid, 200);
    if (entries.length < 14) return predictions;

    // Extract daily counts
    const dayMs = 86_400_000;
    const timestamps: number[] = [];
    const counts: number[] = [];
    const oldest = entries[entries.length - 1].timestamp;
    const newest = entries[0].timestamp;
    for (let t = oldest; t <= newest; t += dayMs) {
      timestamps.push(t);
      counts.push(entries.filter(e => e.timestamp >= t && e.timestamp < t + dayMs).length);
    }

    if (counts.length >= 14) {
      predictions.push(...this.detectPeriodicity(counts));
      predictions.push(...this.detectTrend(counts));
      predictions.push(...this.detectAnomalies(counts));
      predictions.push(...await this.forecast(counts));
    }

    predictions.push(...this.detectTopicShifts(entries));

    this.cachedPredictions = predictions;
    return predictions;
  }

  allPredictions(): PhonePrediction[] {
    return [...this.cachedPredictions, ...this.serverPredictions]
      .sort((a, b) => b.confidence - a.confidence);
  }

  receiveServerPredictions(json: string): void {
    try {
      this.serverPredictions = JSON.parse(json);
    } catch { /* ignore */ }
  }

  startListening(between: BetweenClient, householdId: string): void {
    between.subscribe(`between.${householdId}.*.*.oracle.predictions`, (_sub, data) => {
      this.receiveServerPredictions(new TextDecoder().decode(data));
    });
  }

  // ── Algorithms ─────────────────────────────────────────────────

  private detectPeriodicity(values: number[]): PhonePrediction[] {
    const s = std(values);
    if (s === 0) return [];
    const results: PhonePrediction[] = [];
    for (const lag of [7, 14, 30]) {
      if (lag >= values.length / 2) continue;
      const acf = autocorrelation(values, lag);
      if (acf > 0.3) {
        const period = lag === 7 ? 'weekly' : lag === 14 ? 'biweekly' : 'monthly';
        results.push({
          text: `Your activity has a ${period} pattern (r=${acf.toFixed(2)})`,
          category: 'pattern',
          confidence: Math.min(acf * 0.8 + 0.2, 0.95),
        });
      }
    }
    return results;
  }

  private detectTrend(values: number[]): PhonePrediction[] {
    const recent = values.slice(-14);
    if (recent.length < 7) return [];
    const slope = linearSlope(recent);
    const r2 = rSquared(recent, slope);
    if (Math.abs(r2) < 0.3) return [];
    const direction = slope > 0 ? 'increasing' : 'declining';
    return [{
      text: `Activity is ${direction} over the last 2 weeks`,
      category: 'pattern',
      confidence: Math.min(Math.abs(r2) * 0.7 + 0.3, 0.90),
    }];
  }

  private detectAnomalies(values: number[]): PhonePrediction[] {
    if (values.length < 14) return [];
    const baseline = values.slice(0, -3);
    const recent = values.slice(-3);
    const m = mean(baseline);
    const s = std(baseline);
    if (s === 0) return [];
    return recent.flatMap(v => {
      const z = (v - m) / s;
      if (Math.abs(z) < 2.5) return [];
      return [{
        text: `Unusual ${z > 0 ? 'spike' : 'drop'}: ${Math.round(v)} events (baseline: ${Math.round(m)} ± ${Math.round(s)})`,
        category: 'anomaly',
        confidence: Math.min(0.6 + Math.abs(z) / 8, 0.95),
      }];
    });
  }

  private async forecast(values: number[]): Promise<PhonePrediction[]> {
    if (values.length < 14) return [];

    // Try ONNX TTM model first
    if (this.ttm.isAvailable()) {
      const ttmResult = await this.ttm.forecast(values, 14);
      if (ttmResult.length > 0) {
        const avgPredicted = ttmResult.reduce((s, p) => s + p.predicted, 0) / ttmResult.length;
        const lastValue = values[values.length - 1];
        const change = lastValue !== 0 ? (avgPredicted - lastValue) / Math.abs(lastValue) : 0;
        const direction = change > 0.05 ? 'increasing' : change < -0.05 ? 'declining' : 'stable';
        return [{
          text: `Activity forecast (TTM): ${direction} over next week`,
          category: 'forecast',
          confidence: 0.75,
        }];
      }
    }

    // Fall back to classical linear forecast
    const slope = linearSlope(values);
    const direction = slope > 0.1 ? 'increasing' : slope < -0.1 ? 'declining' : 'stable';
    return [{
      text: `Activity forecast: ${direction} over next week`,
      category: 'forecast',
      confidence: 0.55,
    }];
  }

  private detectTopicShifts(entries: StudyItem[]): PhonePrediction[] {
    if (entries.length < 20) return [];
    const half = Math.floor(entries.length / 2);
    const recentText = entries.slice(0, half).map(e => e.content).join(' ');
    const olderText = entries.slice(half).map(e => e.content).join(' ');
    const recentWords = extractKeywords(recentText);
    const olderWords = extractKeywords(olderText);

    const results: PhonePrediction[] = [];
    for (const [word, count] of recentWords) {
      if (count >= 3 && !olderWords.has(word)) {
        results.push({
          text: `New topic: '${word}' (${count} mentions recently, not seen before)`,
          category: 'topic',
          confidence: Math.min(0.5 + count * 0.05, 0.85),
        });
      }
    }
    return results.slice(0, 3);
  }
}

// ── Math ─────────────────────────────────────────────────────────

function mean(v: number[]): number { return v.length ? v.reduce((a, b) => a + b) / v.length : 0; }
function std(v: number[]): number {
  if (v.length < 2) return 0;
  const m = mean(v);
  return Math.sqrt(v.reduce((s, x) => s + (x - m) ** 2, 0) / v.length);
}
function autocorrelation(v: number[], lag: number): number {
  const m = mean(v), s = std(v);
  if (s === 0 || lag >= v.length) return 0;
  let sum = 0;
  for (let i = 0; i < v.length - lag; i++) sum += (v[i] - m) * (v[i + lag] - m);
  return sum / ((v.length - lag) * s * s);
}
function linearSlope(y: number[]): number {
  const x = y.map((_, i) => i);
  const xm = mean(x), ym = mean(y);
  let num = 0, den = 0;
  for (let i = 0; i < y.length; i++) { num += (x[i] - xm) * (y[i] - ym); den += (x[i] - xm) ** 2; }
  return den ? num / den : 0;
}
function rSquared(y: number[], slope: number): number {
  const ym = mean(y);
  const intercept = ym - slope * mean(y.map((_, i) => i));
  let tot = 0, res = 0;
  for (let i = 0; i < y.length; i++) { tot += (y[i] - ym) ** 2; res += (y[i] - (slope * i + intercept)) ** 2; }
  return tot > 0 ? 1 - res / tot : 0;
}

const STOPS = new Set('the a an is are was were be been have has had do does did will would shall should may might can could this that these those and or but not my your his her its our their for with from about into through during before after above below'.split(' '));
function extractKeywords(text: string): Map<string, number> {
  const words = (text.toLowerCase().match(/[a-z]{3,}/g) ?? []).filter(w => !STOPS.has(w));
  const counts = new Map<string, number>();
  for (const w of words) counts.set(w, (counts.get(w) ?? 0) + 1);
  return counts;
}
