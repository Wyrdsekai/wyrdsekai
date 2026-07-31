const serverInput = document.getElementById('server');
const enabledCheckbox = document.getElementById('enabled');
const statusDiv = document.getElementById('status');

// Load saved settings
chrome.storage.sync.get(['serverUrl', 'enabled'], (result) => {
  serverInput.value = result.serverUrl || 'http://localhost:7070';
  enabledCheckbox.checked = result.enabled !== false;
  checkHealth(serverInput.value);
});

// Save on change
serverInput.addEventListener('change', () => {
  chrome.storage.sync.set({ serverUrl: serverInput.value });
  checkHealth(serverInput.value);
});

enabledCheckbox.addEventListener('change', () => {
  chrome.storage.sync.set({ enabled: enabledCheckbox.checked });
});

async function checkHealth(url) {
  try {
    const resp = await fetch(`${url}/health`, { signal: AbortSignal.timeout(3000) });
    if (resp.ok) {
      const data = await resp.json();
      statusDiv.textContent = `Connected to ${data.name || 'Wyrdsekai'} (v${data.version || '?'})`;
      statusDiv.className = 'status ok';
    } else {
      statusDiv.textContent = `Server returned ${resp.status}`;
      statusDiv.className = 'status err';
    }
  } catch (e) {
    statusDiv.textContent = 'Not connected — check server URL';
    statusDiv.className = 'status err';
  }
}
