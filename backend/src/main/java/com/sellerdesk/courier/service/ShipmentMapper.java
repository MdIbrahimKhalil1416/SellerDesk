package com.sellerdesk.courier.service;

import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.courier.dto.ShipmentResponse;
import com.sellerdesk.courier.dto.ShipmentSummary;
import com.sellerdesk.courier.model.Shipment;
import com.sellerdesk.courier.model.ShipmentStatus;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Entity → DTO, by hand (docs/ARCHITECTURE.md §2). Never exposes {@code shopId} or {@code eventKey}. */
@Component
@RequiredArgsConstructor
public class ShipmentMapper {

    private final CurrentUser currentUser;

    public ShipmentResponse toResponse(Shipment s) {
        List<ShipmentResponse.Event> events = s.getEvents().stream()
                .map(e -> new ShipmentResponse.Event(e.at(), e.source(), e.status(), e.providerStatus(), e.ignored(), e.note()))
                .toList();
        ShipmentResponse.Recipient recipient = s.getRecipient() == null ? null
                : new ShipmentResponse.Recipient(s.getRecipient().name(), s.getRecipient().phone(), s.getRecipient().address());
        return new ShipmentResponse(s.getId(), s.getOrderId(), s.getOrderNumber(), s.getProvider(), s.getConsignmentId(),
                s.getTrackingCode(), s.getStatus(), s.getCodAmount(), s.getCollectedAmount(),
                Boolean.TRUE.equals(s.getCodMismatch()), s.getCourierFee(), recipient, s.getNote(), s.getFailureReason(),
                Boolean.TRUE.equals(s.getNeedsManualCheck()), s.getCancelReason(), events, allowedActions(s),
                new ShipmentResponse.Actor(s.getCreatedBy(), s.getCreatedByName()), s.getCreatedAt(), s.getUpdatedAt());
    }

    public static ShipmentSummary toSummary(Shipment s) {
        return new ShipmentSummary(s.getId(), s.getOrderId(), s.getOrderNumber(), s.getProvider(), s.getConsignmentId(),
                s.getTrackingCode(), s.getStatus(), s.getCodAmount(), s.getCollectedAmount(),
                Boolean.TRUE.equals(s.getCodMismatch()), s.getFailureReason(), Boolean.TRUE.equals(s.getNeedsManualCheck()),
                s.getCreatedAt(), s.getUpdatedAt());
    }

    List<ShipmentResponse.Action> allowedActions(Shipment s) {
        List<ShipmentResponse.Action> actions = new ArrayList<>();
        ShipmentStatus status = s.getStatus();
        if (currentUser.hasPermission(Permission.ORDER_VIEW) && s.getConsignmentId() != null && status.active()) {
            actions.add(ShipmentResponse.Action.TRACK);
        }
        if (currentUser.hasPermission(Permission.ORDER_FULFILL)
                && (status == ShipmentStatus.BOOKING || status == ShipmentStatus.CREATED)) {
            actions.add(ShipmentResponse.Action.CANCEL);
        }
        return actions;
    }
}
