const { getDefaultConfig } = require('expo/metro-config');

/** @type {import('expo/metro-config').MetroConfig} */
const config = getDefaultConfig(__dirname);

// Web-only dependencies (@mlc-ai/web-llm, nats.ws) transitively require Node.js
// built-in modules (url, util, crypto, etc.). These code paths never execute on
// iOS/Android — they're gated behind Platform.OS === 'web' in App.tsx — but Metro's
// static analysis still traces into them and fails to resolve the builtins.
//
// Fix: return empty modules for all Node.js builtins on native platforms.
// On web, the real modules are available via the browser/polyfill environment.
const NODE_BUILTINS = new Set([
  'assert', 'buffer', 'child_process', 'cluster', 'crypto', 'dgram', 'dns',
  'domain', 'events', 'fs', 'http', 'https', 'module', 'net', 'os', 'path',
  'punycode', 'querystring', 'readline', 'repl', 'stream', 'string_decoder',
  'sys', 'timers', 'tls', 'tty', 'url', 'util', 'v8', 'vm', 'worker_threads',
  'zlib',
]);

const upstreamResolveRequest = config.resolver?.resolveRequest;

config.resolver = {
  ...config.resolver,
  resolveRequest: (context, moduleName, platform) => {
    if (platform !== 'web' && NODE_BUILTINS.has(moduleName)) {
      return { type: 'empty' };
    }
    if (upstreamResolveRequest) {
      return upstreamResolveRequest(context, moduleName, platform);
    }
    return context.resolveRequest(context, moduleName, platform);
  },
};

module.exports = config;
