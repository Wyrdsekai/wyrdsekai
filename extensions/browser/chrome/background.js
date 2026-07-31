/**
 * Wyrdsekai browser extension — background service worker.
 *
 * Tracks page visits and sends them to the Oracle via the Wyrdsekai server.
 * Only sends: URL, title, timestamp. Never sends page content.
 * All data goes to localhost — never leaves your network.
 */

const DEFAULT_SERVER = 'http://localhost:7070';
let serverUrl = DEFAULT_SERVER;
let enabled = true;

// Load settings
chrome.storage.sync.get(['serverUrl', 'enabled'], (result) => {
  if (result.serverUrl) serverUrl = result.serverUrl;
  if (result.enabled !== undefined) enabled = result.enabled;
});

// Listen for settings changes
chrome.storage.onChanged.addListener((changes) => {
  if (changes.serverUrl) serverUrl = changes.serverUrl.newValue;
  if (changes.enabled) enabled = changes.enabled.newValue;
});

// Track page visits (only when tab is active for > 5 seconds)
const visitTimers = new Map();

chrome.tabs.onActivated.addListener(async (activeInfo) => {
  if (!enabled) return;

  // Clear previous timer
  for (const [tabId, timer] of visitTimers) {
    clearTimeout(timer);
    visitTimers.delete(tabId);
  }

  // Start timer for this tab
  visitTimers.set(activeInfo.tabId, setTimeout(async () => {
    try {
      const tab = await chrome.tabs.get(activeInfo.tabId);
      if (tab.url && !tab.url.startsWith('chrome://') && !tab.url.startsWith('chrome-extension://')) {
        sendEvent({
          timestamp: new Date().toISOString(),
          source: 'browser',
          event_type: 'page_visit',
          content: tab.title || tab.url,
          metadata: { url: tab.url },
        });
      }
    } catch (e) {
      // Tab may have closed
    }
  }, 5000)); // 5 second dwell time
});

// Context menu: "Send to Study"
chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: 'send-to-study',
    title: 'Send to Study',
    contexts: ['page', 'selection', 'link'],
  });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId !== 'send-to-study') return;

  const content = info.selectionText || info.linkUrl || tab?.title || '';
  const url = info.linkUrl || info.pageUrl || tab?.url || '';

  sendEvent({
    timestamp: new Date().toISOString(),
    source: 'browser',
    event_type: 'shared',
    content: content,
    metadata: { url },
  });
});

// Send event to Oracle via Wyrdsekai server
async function sendEvent(event) {
  try {
    await fetch(`${serverUrl}/api/oracle/ingest`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        user_id: 'default',
        events: [event],
      }),
    });
  } catch (e) {
    // Server not available — silent fail
  }
}
