/**
 * The elapsed-time context for her prompt: how long since the person speaking
 * now last spoke. TypeScript port of KMP's TimeContext.kt (core
 * agent/TimeContext.java), the same words and the same rounding.
 *
 * The date and the time are not here: every request says what it knows about
 * today and the send point stamps it (inference/NowLine).
 */

const MINUTE_MS = 60_000;

/** `Last heard from you: 3 hours ago.`, or empty when there is nothing to say (never, or under a minute). */
export function buildTimeContext(lastHumanSaidMs: number | null, nowMs: number = Date.now()): string {
  if (lastHumanSaidMs == null) return '';
  const elapsedMs = nowMs - lastHumanSaidMs;
  if (Math.floor(elapsedMs / MINUTE_MS) < 1) return '';
  return `Last heard from you: ${formatDuration(elapsedMs)} ago.`;
}

export function formatDuration(ms: number): string {
  const totalMinutes = Math.floor(ms / MINUTE_MS);
  if (totalMinutes < 2) return 'a moment';
  if (totalMinutes < 60) return `${totalMinutes} minutes`;
  const hours = Math.floor(totalMinutes / 60);
  const remainingMinutes = totalMinutes - hours * 60;
  if (hours < 24) {
    if (remainingMinutes === 0) return `${hours} ${hours === 1 ? 'hour' : 'hours'}`;
    return `${hours}h ${remainingMinutes}m`;
  }
  const days = Math.floor(hours / 24);
  const remainingHours = hours - days * 24;
  if (remainingHours === 0) return `${days} ${days === 1 ? 'day' : 'days'}`;
  return `${days} ${days === 1 ? 'day' : 'days'} ${remainingHours}h`;
}
