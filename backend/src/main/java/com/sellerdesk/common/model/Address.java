package com.sellerdesk.common.model;

/**
 * Embedded address used by shops, customers and orders (docs/SCHEMA.md §5).
 * Stored inside the parent document, never in its own collection.
 *
 * @param line     house / road / area, free text
 * @param area     thana / upazila (optional)
 * @param district district name
 * @param zone     decides the default delivery charge
 * @param note     landmark (optional)
 */
public record Address(String line, String area, String district, DeliveryZone zone, String note) {
}
