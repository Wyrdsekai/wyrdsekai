package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.economy.TradingPostService;
import org.wyrdsekai.core.soul.FamilyLocker;

import java.util.Optional;

/**
 * Bridges {@link ThoughtForm} authorship into the existing
 * {@link TradingPostService} economy.
 *
 * <p>On post: the form is serialized as JSON and stashed in the
 * PostedItem's description field alongside a human-readable preview;
 * on buy: the JSON is deserialized, passed through {@link FormTransfer#copy}
 * to give the buyer a fresh fork, and dropped into the buyer's locker
 * via {@link FamilyLocker#acceptCopy}.</p>
 *
 * <p>The Trading Post already has trust scores, provenance entries, and
 * quarantine — this bridge just threads form-specific serialization
 * through its interface.</p>
 */
public final class TradingPostBridge {

    private static final Logger log = LoggerFactory.getLogger(TradingPostBridge.class);

    /** Marker prefix in the PostedItem description that signals a form listing. */
    public static final String FORM_DESCRIPTION_PREFIX = "[thought-form] ";

    /** JSON marker boundary used to separate preview from the serialized form. */
    private static final String PAYLOAD_BOUNDARY = "\n---payload---\n";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .findAndRegisterModules();

    private TradingPostBridge() {}

    /**
     * List a thought form on the Trading Post.
     *
     * <p>Builds a {@link FormListing} view for the human-readable part and
     * stashes the full form JSON so the buyer can reconstruct the fork on
     * purchase. Only active (non-retired) forms may be listed.</p>
     */
    public static TradingPostService.PostedItem postForm(
            TradingPostService service,
            ThoughtForm form,
            String sellerDid,
            String sellerName,
            long price,
            String exampleOutput) {
        if (service == null) throw new IllegalArgumentException("service required");
        if (form == null) throw new IllegalArgumentException("form required");

        var listing = FormListing.from(form, sellerDid, price, exampleOutput);
        String json;
        try {
            json = MAPPER.writeValueAsString(form);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize form for listing", e);
        }
        var description = FORM_DESCRIPTION_PREFIX + listing.displayLine()
            + (exampleOutput == null ? "" : "\n\nexample: " + exampleOutput)
            + PAYLOAD_BOUNDARY + json;
        return service.postItem(form.name(), description, price, sellerDid, sellerName);
    }

    /**
     * Buy a listed form. Produces a forked copy owned by {@code buyerDid},
     * accepts it into the buyer's locker, and marks the listing SOLD.
     * Returns the new forked form, or empty if the item wasn't a form
     * listing or isn't available.
     */
    public static Optional<ThoughtForm> buyForm(
            TradingPostService service,
            String itemId,
            String buyerDid,
            FamilyLocker buyerLocker) {
        if (service == null || itemId == null || buyerDid == null || buyerLocker == null) {
            return Optional.empty();
        }
        var itemOpt = service.getItem(itemId);
        if (itemOpt.isEmpty()) return Optional.empty();
        var item = itemOpt.get();
        if (!item.description().startsWith(FORM_DESCRIPTION_PREFIX)) return Optional.empty();

        var payloadIdx = item.description().indexOf(PAYLOAD_BOUNDARY);
        if (payloadIdx < 0) {
            log.warn("TradingPostBridge: listing {} has no payload boundary", itemId);
            return Optional.empty();
        }
        var json = item.description().substring(payloadIdx + PAYLOAD_BOUNDARY.length());
        ThoughtForm source;
        try {
            source = MAPPER.readValue(json, ThoughtForm.class);
        } catch (Exception e) {
            log.warn("TradingPostBridge: failed to deserialize form from listing {}: {}",
                itemId, e.getMessage());
            return Optional.empty();
        }

        // Fork + accept
        var copy = FormTransfer.copy(source, buyerDid,
            FormTransfer.Intent.PURCHASE,
            "purchased from " + item.sellerName() + " for " + item.price() + " CU");
        try {
            buyerLocker.acceptCopy(copy, buyerDid);
        } catch (Exception e) {
            log.warn("TradingPostBridge: buyer locker rejected accepted copy: {}",
                e.getMessage());
            return Optional.empty();
        }

        // Mark SOLD via the service's own acquire path
        service.acquireItem(itemId, buyerDid);

        log.info("TradingPostBridge: {} bought form '{}' ({}:{})",
            buyerDid, source.name(), itemId, item.price());
        return Optional.of(copy);
    }
}
