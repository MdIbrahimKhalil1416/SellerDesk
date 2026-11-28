package com.sellerdesk.courier.dto;

/**
 * Answer to a courier webhook. All three are {@code 200}: retrying would change nothing.
 *
 * <ul>
 *   <li>{@code PROCESSED}: applied (or appended as an event).</li>
 *   <li>{@code DUPLICATE}: this event was already received.</li>
 *   <li>{@code IGNORED}: an illegal status jump; stored for debugging, nothing changed.</li>
 * </ul>
 */
public record WebhookAck(Result result) {

    public enum Result {
        PROCESSED,
        DUPLICATE,
        IGNORED
    }
}
