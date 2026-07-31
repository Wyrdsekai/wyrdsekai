/**
 * Phone-local soul item representation.
 * TypeScript port of the server SoulItem, adapted for local storage.
 * Uses a simple string hash (no crypto needed for phone items).
 */

export interface PhoneSoulItem {
  hash: string;
  category: string;
  label: string;
  text: string;
  creatorDid: string;
  created: number;
  significance: number;
  tags: string[];
}

/**
 * Simple string hash (djb2 variant). Not cryptographic — just for
 * content-addressing phone items within local storage.
 */
function djb2Hash(str: string): string {
  let hash = 5381;
  for (let i = 0; i < str.length; i++) {
    // hash * 33 + char
    hash = ((hash << 5) + hash + str.charCodeAt(i)) | 0;
  }
  // Convert to unsigned hex
  return (hash >>> 0).toString(16).padStart(8, '0');
}

/**
 * Create a new PhoneSoulItem with an auto-computed content hash.
 */
export function createPhoneSoulItem(
  category: string,
  label: string,
  text: string,
  creatorDid: string,
  significance: number,
  tags: string[],
): PhoneSoulItem {
  const created = Date.now();
  const hashInput = `${category}:${label}:${text}:${creatorDid}:${created}`;
  const hash = djb2Hash(hashInput);

  return {
    hash,
    category,
    label,
    text,
    creatorDid,
    created,
    significance: Math.max(0, Math.min(1, significance)),
    tags: [...tags],
  };
}
