/**
 * One leading system message per request.
 *
 * The prompt assembler sends each layer as its own system message (identity,
 * soul, room, vitality, recency anchor, …), up to ten of them. The Qwen chat
 * template refuses a system message anywhere but index 0 ("System message must
 * be at the beginning"): llama-server answers 400, and llama.rn formats with the
 * model's own template on the device. WebLLM throws SystemMessageOrderError.
 *
 * Port of the server's PromptAssembler.mergeConsecutiveSystemMessages:
 *   - the leading run of system messages becomes one, joined with a blank line
 *     in layer order;
 *   - a system message after the conversation has started is folded into the
 *     message before it as `[system note: …]`, never left mid-list.
 * The input is never changed.
 *
 * The send points run this before NowLine stamps, so the DATE line opens the
 * one system message and a DATE_TIME line with no user turn to open is put
 * after it rather than between two layers.
 */

import type { ChatMessage } from './types';

export function consolidateSystemMessages(messages: ChatMessage[]): ChatMessage[] {
  if (messages.length < 2) return messages;
  const out: ChatMessage[] = [];
  let run: string[] = [];
  let conversationStarted = false;

  const flush = () => {
    if (run.length === 0) return;
    const text = run.join('\n\n');
    run = [];
    if (!conversationStarted) {
      out.push({ role: 'system', content: text });
      return;
    }
    const prev = out[out.length - 1];
    out[out.length - 1] = { ...prev, content: `${prev.content ?? ''}\n\n[system note: ${text}]` };
  };

  for (const m of messages) {
    if (m.role === 'system') {
      run.push(m.content ?? '');
      continue;
    }
    flush();
    out.push(m);
    conversationStarted = true;
  }
  flush();
  return out;
}
