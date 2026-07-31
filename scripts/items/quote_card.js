// literary-quote card.
//
// Searches Open Library for a book and Unsplash for a matching cover image,
// then assembles a small "quote card" object. Demonstrates:
//   * Two Phase W adapter calls in a single item invocation (openlib + unsplash)
//   * Tier 1 public reads (no rate-limit declaration required for openlib;
//     unsplash search is also Tier 1 per the §4.45 capability table)
//   * Graceful degradation when an adapter returns not_yet_wired or
//     credential_missing — the script returns the partial result rather
//     than failing the whole item.
//
// Manifest fields:
//   capabilities: ["openlib.read", "unsplash.read"]
//   external_domains: ["openlibrary.org", "api.unsplash.com", "*.unsplash.com"]
exports.manifest = {
  name: "quote_card",
  version: "1.0.0",
  description: "Compose a literary quote card from Open Library + Unsplash.",
  author: "did:wyrd:system",
  capabilities: ["openlib.read", "unsplash.read"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "A composed card slides forward — quoted line above, attribution beneath, lamp-light pooled on it."
  },
  external_domains: ["openlibrary.org", "api.unsplash.com", "*.unsplash.com"],
  data_sensitivity: "low",
  // Items-as-tools contract — invoke() reads params.query/params.title
  // (required) + params.quote, not the args string; a bare invoke explains
  // that a book title or author is needed.
  commands: [
    { label: "Compose a quote card (needs a book title)", args: "" }
  ]
};

function invoke(params) {
  var query = params.query || params.title || "";
  var quote = params.quote || "";
  if (!query) {
    return { ok: false, error: "query is required (book title or author)" };
  }

  // Step 1 — book metadata via Open Library (auth-free).
  var bookHits = world.openlib.search({ query: query, limit: 1 });
  var book = null;
  if (bookHits && bookHits.success && bookHits.data && bookHits.data.length > 0) {
    book = bookHits.data[0];
  }

  // Step 2 — matching cover/mood image via Unsplash. Falls back gracefully
  // when the steward hasn't populated unsplash.access_key.
  var image = null;
  var imageStatus = "skipped";
  var imageQuery = (book && book.title) ? book.title : query;
  var photos = world.unsplash.search({ query: imageQuery, perPage: 1 });
  if (photos && photos.success && photos.data && photos.data.length > 0) {
    image = photos.data[0];
    imageStatus = "ok";
  } else if (photos && !photos.success && photos.error) {
    imageStatus = photos.error.code || "error";
  }

  // Compose the card.
  var card = {
    quote: quote,
    book: book ? {
      title: book.title,
      authors: book.authors,
      firstPublishYear: book.firstPublishYear,
      coverId: book.coverId,
      coverUrl: book.coverId
        ? "https://covers.openlibrary.org/b/id/" + book.coverId + "-L.jpg"
        : null
    } : { title: query, found: false },
    image: image ? {
      photoId: image.photoId,
      urls: image.urls,
      attribution: image.user
        ? "Photo by " + (image.user.name || image.user.username) + " on Unsplash"
        : "Photo via Unsplash"
    } : { available: false, reason: imageStatus },
    composedAt: world.time.iso()
  };

  return {
    ok: true,
    card: card,
    summary: "Quote card composed for: " + (card.book.title || query)
  };
}
