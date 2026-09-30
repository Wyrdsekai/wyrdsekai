/**
 * Every model request in the phone tree says what it knows about today.
 *
 * A path that declares nothing still gets DATE_TIME at the router, but the
 * date went missing before exactly by paths nobody declared; so every call
 * site says DATE_TIME, DATE or NONE out loud (NowLine). Scans src/ with the
 * TypeScript parser:
 *   - a model call — `.complete(…)` on anything but the offline queue,
 *     `infer(…)`, `completeViaRemote` / `completeViaHttp` / `completeAt`, or any call whose
 *     options carry `maxTokens` — passes `{ …, now: … }`, or forwards its own
 *     caller's `options` / `opts` unchanged (a wrapper; its caller declares) —
 *     a parameter of the function it sits in, not a local of that name;
 *   - a backend called around the routers (llamaService / webLLMService) is
 *     not stamped, so only NONE may go that way;
 *   - a file that posts to `/v1/chat/completions` itself stamps what it sends.
 */

import * as fs from 'fs';
import * as path from 'path';
import ts from 'typescript';

const SRC = path.resolve(__dirname, '../../src');
const ROUTERS = new Set(['inference/InferenceRouter.ts', 'web/WebInferenceRouter.ts']);
const BACKENDS = new Set(['llamaService', 'webLLMService']);
const PASS_THROUGH = new Set(['options', 'opts']);

function sourceFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) return e.name === '__tests__' ? [] : sourceFiles(p);
    return /\.tsx?$/.test(e.name) ? [p] : [];
  });
}

interface ModelCall {
  file: string;
  line: number;
  text: string;
  receiver: string | null;
  declares: 'now' | 'pass-through' | null;
  nowText: string | null;
}

function propertyNamed(obj: ts.ObjectLiteralExpression, name: string): ts.PropertyAssignment | undefined {
  return obj.properties.find(
    (p): p is ts.PropertyAssignment => ts.isPropertyAssignment(p) && p.name.getText() === name,
  );
}

/** `options` forwarded unchanged: a parameter of the nearest enclosing function. */
function isCallersOptions(arg: ts.Expression | undefined): boolean {
  if (!arg || !ts.isIdentifier(arg) || !PASS_THROUGH.has(arg.text)) return false;
  let fn: ts.Node | undefined = arg.parent;
  while (fn && !ts.isFunctionLike(fn)) fn = fn.parent;
  return !!fn && fn.parameters.some((p) => ts.isIdentifier(p.name) && p.name.text === arg.text);
}

interface Source {
  file: string;
  text: string;
}

const TREE: Source[] = sourceFiles(SRC).map((abs) => ({
  file: path.relative(SRC, abs),
  text: fs.readFileSync(abs, 'utf8'),
}));

function scan(tree: Source[]) {
  const calls: ModelCall[] = [];
  const rawSends: { file: string; stamps: boolean }[] = [];

  for (const { file, text } of tree) {
    const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true,
      file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    let postsChat = false;
    let stamps = false;

    const visit = (node: ts.Node): void => {
      if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateTail(node)
          || ts.isTemplateMiddle(node)) && node.text.includes('/v1/chat/completions')) {
        postsChat = true;
      }
      if (ts.isCallExpression(node)) {
        const callee = node.expression;
        const name = ts.isPropertyAccessExpression(callee) ? callee.name.text
          : ts.isIdentifier(callee) ? callee.text : null;
        const receiver = ts.isPropertyAccessExpression(callee)
          ? (ts.isPropertyAccessExpression(callee.expression) ? callee.expression.name.text
            : callee.expression.getText(sf))
          : null;
        if (name === 'stamp') stamps = true;

        const last = node.arguments[node.arguments.length - 1];
        const options = last && ts.isObjectLiteralExpression(last) ? last : null;
        const isModelCall =
          (name === 'complete' && receiver !== null && !/queue/i.test(receiver))
          || name === 'infer'
          || name === 'completeViaRemote' || name === 'completeViaHttp' || name === 'completeAt'
          || (options !== null && propertyNamed(options, 'maxTokens') !== undefined);
        if (isModelCall) {
          const now = options ? propertyNamed(options, 'now') : undefined;
          calls.push({
            file,
            line: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1,
            text: node.getText(sf).split('\n')[0],
            receiver,
            declares: now ? 'now' : isCallersOptions(last) ? 'pass-through' : null,
            nowText: now ? now.initializer.getText(sf) : null,
          });
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(sf);
    if (postsChat) rawSends.push({ file, stamps });
  }
  return { calls, rawSends };
}

describe('every model request declares what it knows about today', () => {
  const { calls, rawSends } = scan(TREE);

  it('the scan sees the known model calls (so a silent miss cannot pass)', () => {
    const files = new Set(calls.map((c) => c.file));
    for (const f of [
      'engine/agent/CompanionEngine.ts',
      'engine/agent/TriageClassifier.ts',
      'engine/soul/LlmExtractor.ts',
      'engine/soul/IdentityEvolver.ts',
      'screens/ModelDownloadScreen.tsx',
      'inference/InferenceRouter.ts',
      'web/WebInferenceRouter.ts',
    ]) {
      expect(files).toContain(f);
    }
    expect(calls.filter((c) => c.file === 'engine/agent/CompanionEngine.ts').length).toBeGreaterThanOrEqual(7);
  });

  it('each one passes now: …, or forwards its caller\'s options', () => {
    const undeclared = calls
      .filter((c) => c.declares === null)
      .map((c) => `${c.file}:${c.line}  ${c.text}`);
    expect(undeclared).toEqual([]);
  });

  it('a local named options is not a caller\'s options: only a parameter passes through', () => {
    const { calls: probe } = scan([{
      file: 'probe.ts',
      text: `
        class Probe {
          async local(msgs) {
            const options = { maxTokens: 64, temperature: 0.7 };
            return this.inferenceClient.complete('voice', msgs, options);
          }
          async forwards(msgs, options) {
            return this.inferenceClient.complete('voice', msgs, options);
          }
          async nested(msgs, opts) {
            return [1].map(() => this.inferenceClient.complete('voice', msgs, opts));
          }
        }`,
    }]);
    expect(probe.map((c) => c.declares)).toEqual([null, 'pass-through', null]);
  });

  it('a backend called around the routers is sent only with NONE', () => {
    const around = calls
      .filter((c) => c.receiver !== null && BACKENDS.has(c.receiver) && !ROUTERS.has(c.file))
      .filter((c) => c.nowText !== 'NowLine.NONE')
      .map((c) => `${c.file}:${c.line}  now: ${c.nowText ?? '(forwarded)'}`);
    expect(around).toEqual([]);
  });

  it('a file that posts to /v1/chat/completions itself stamps what it sends', () => {
    // CompanionEngine no longer posts itself: its household call goes through
    // InferenceRouter.completeAt, which stamps.
    expect(rawSends.map((r) => r.file)).toEqual(expect.arrayContaining([
      'engine/soul/SoulAuthoring.ts',
      'inference/InferenceRouter.ts',
    ]));
    expect(rawSends.filter((r) => !r.stamps).map((r) => r.file)).toEqual([]);
  });
});
