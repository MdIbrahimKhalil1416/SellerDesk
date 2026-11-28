package com.sellerdesk.courier.controller;

import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.common.error.BusinessRuleException;
import com.sellerdesk.courier.dto.WebhookAck;
import com.sellerdesk.courier.service.CourierWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Courier webhooks (docs/modules/COURIER.md §5). <b>Public</b> (no JWT): {@code SecurityConfig}
 * lists the path. The proof is the signature, checked before anything else.
 *
 * <p>The body is read as <b>raw bytes</b>, at most {@link #MAX_BODY_BYTES}: a signature is computed
 * over the exact bytes, so the JSON must not be parsed and re-written before the check.
 */
@Tag(name = "Courier webhooks", description = "Called by couriers; signed, no token")
@RestController
@RequestMapping("/api/v1/webhooks/courier")
@RequiredArgsConstructor
public class CourierWebhookController {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final CourierWebhookService webhookService;

    @Operation(summary = "Courier status webhook", description = "Signed by the courier (mock: X-SD-Timestamp, "
            + "X-SD-Signature). Idempotent: a repeated event answers DUPLICATE; an illegal status jump answers IGNORED.")
    @ApiResponse(responseCode = "200", description = "PROCESSED, DUPLICATE or IGNORED")
    @ApiResponse(responseCode = "401", description = "INVALID_SIGNATURE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: unknown courier or shipment", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "PAYLOAD_TOO_LARGE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{provider}")
    public WebhookAck receive(@PathVariable String provider, HttpServletRequest request) throws IOException {
        return webhookService.handle(provider, headers(request), readBody(request));
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw tooLarge();
            }
            return body;
        }
    }

    private static HttpHeaders headers(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    private static BusinessRuleException tooLarge() {
        return new BusinessRuleException(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Webhook body is larger than 64 KB");
    }
}
