/**
 * Bud delegation's HTTP path carries her question and the device token to the
 * home: never over plain http to another machine ( W2).
 */
import { BudDelegation } from '../../../src/engine/between/BudDelegation';

const fetchMock = jest.fn(async () => ({ ok: true, json: async () => ({ text: 'from home' }) }));
beforeEach(() => {
  fetchMock.mockClear();
  (globalThis as { fetch: unknown }).fetch = fetchMock;
});

const bud = (serverUrl: string) =>
  new BudDelegation({ between: null, nodeId: 'n', familyId: 'f', serverUrl, deviceToken: 'wyrd_dev_x' });

it('asks the home over https', async () => {
  expect(await bud('https://198.51.100.20:7443').delegate({ message: 'hi' })).toEqual({ text: 'from home', actions: [] });
  expect((fetchMock.mock.calls[0] as unknown[])[0]).toBe('https://198.51.100.20:7443/api/companion/ask');
});

it('sends nothing to a plain http address on the network', async () => {
  expect(await bud('http://198.51.100.20:7070').delegate({ message: 'hi' })).toBeNull();
  expect(fetchMock).not.toHaveBeenCalled();
});
