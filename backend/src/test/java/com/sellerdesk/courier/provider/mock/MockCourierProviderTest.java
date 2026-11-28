package com.sellerdesk.courier.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.courier.model.Recipient;
import com.sellerdesk.courier.model.ShipmentStatus;
import com.sellerdesk.courier.provider.BookingResult;
import com.sellerdesk.courier.provider.CancelResult;
import com.sellerdesk.courier.provider.CourierProperties;
import com.sellerdesk.courier.provider.CourierProperties.MockMode;
import com.sellerdesk.courier.provider.CreateShipmentCommand;
import com.sellerdesk.courier.provider.InvalidSignatureException;
import com.sellerdesk.courier.provider.InvalidWebhookPayloadException;
import com.sellerdesk.courier.provider.TrackResult;
import com.sellerdesk.courier.provider.WebhookEvent;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;

class MockCourierProviderTest {

    private static final String SECRET = "a-secret-that-is-long-enough-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

    private static MockCourierProvider mock(MockMode mode) {
        CourierProperties props = new CourierProperties(SECRET, false, false, Duration.ofSeconds(3), Duration.ofSeconds(10),
                new CourierProperties.Mock(true, mode,
                        new CourierProperties.Simulator(false, Duration.ofSeconds(30), "http://localhost", 10)));
        return new MockCourierProvider(props, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CreateShipmentCommand command(String ref) {
        return new CreateShipmentCommand(ref, "SD-7", new Recipient("Rahim", "01712345678",
                new Address("House 5", null, "Dhaka", DeliveryZone.INSIDE_DHAKA, null)), 96_000, null, 2);
    }

    @Test
    void bookingGivesMockIdsAndIsIdempotentOnMerchantRef() {
        MockCourierProvider mock = mock(MockMode.OK);
        BookingResult.Created first = (BookingResult.Created) mock.createShipment(command("ref-1"));
        assertThat(first.consignmentId()).matches("MOCK-\\d{10}");
        assertThat(first.trackingCode()).matches("MCK[A-Z0-9]{8}");
        assertThat(mock.createShipment(command("ref-1"))).isEqualTo(first);
        assertThat(((BookingResult.Created) mock.createShipment(command("ref-2"))).consignmentId())
                .isNotEqualTo(first.consignmentId());
    }

    @Test
    void timeoutStillCreatesTheParcelLikeALostResponse() {
        MockCourierProvider mock = mock(MockMode.OK);
        mock.nextCalls(MockMode.TIMEOUT);
        assertThatThrownBy(() -> mock.createShipment(command("ref-1"))).isInstanceOf(UncheckedIOException.class);
        assertThat(mock.findByMerchantRef("ref-1")).isPresent();
        assertThat(mock.findByMerchantRef("unknown")).isEmpty();
    }

    @Test
    void rejectModeRefusesAndFlakyFailsEverySecondCall() {
        assertThat(mock(MockMode.REJECT).createShipment(command("r"))).isInstanceOf(BookingResult.Rejected.class);
        MockCourierProvider flaky = mock(MockMode.FLAKY);
        assertThat(flaky.createShipment(command("a"))).isInstanceOf(BookingResult.Created.class);
        assertThatThrownBy(() -> flaky.createShipment(command("b"))).isInstanceOf(UncheckedIOException.class);
        assertThat(flaky.createShipment(command("c"))).isInstanceOf(BookingResult.Created.class);
    }

    @Test
    void trackMapsStatusesAndCancelOnlyBeforePickup() {
        MockCourierProvider mock = mock(MockMode.OK);
        String c = ((BookingResult.Created) mock.createShipment(command("ref"))).consignmentId();
        assertThat(((TrackResult.Ok) mock.track(c)).update().status()).isEqualTo(ShipmentStatus.CREATED);
        mock.advance(c, "picked_up");
        assertThat(((TrackResult.Ok) mock.track(c)).update().status()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(mock.cancel(c)).isInstanceOf(CancelResult.Rejected.class);
        mock.advance(c, "delivered");
        TrackResult.Ok delivered = (TrackResult.Ok) mock.track(c);
        assertThat(delivered.update().collectedAmount()).isEqualTo(96_000);
        assertThat(delivered.update().courierFee()).isEqualTo(MockCourierProvider.FEE_POISHA);

        String other = ((BookingResult.Created) mock.createShipment(command("ref-2"))).consignmentId();
        assertThat(mock.cancel(other)).isInstanceOf(CancelResult.Ok.class);
        assertThat(mock.cancel(other)).isInstanceOf(CancelResult.Ok.class); // twice is harmless
        assertThat(mock.track("MOCK-0000000000")).isInstanceOf(TrackResult.Rejected.class);
    }

    @Test
    void webhookIsVerifiedThenMappedAndIgnoresShopIdInTheBody() {
        MockCourierProvider mock = mock(MockMode.OK);
        byte[] body = """
                {"eventId":"e1","consignmentId":"MOCK-1","status":"delivered","time":"2026-11-28T09:59:00Z",
                 "collectedAmount":90000,"courierFee":6000,"shopId":"someone-else","orderId":"x"}"""
                .getBytes(StandardCharsets.UTF_8);
        long ts = NOW.getEpochSecond();
        HttpHeaders headers = new HttpHeaders();
        headers.add(WebhookSigner.TIMESTAMP_HEADER, Long.toString(ts));
        headers.add(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign(SECRET, ts, body));

        WebhookEvent event = mock.parseWebhook(headers, body);
        assertThat(event.consignmentId()).isEqualTo("MOCK-1");
        assertThat(event.update().status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(event.update().providerStatus()).isEqualTo("delivered");
        assertThat(event.update().providerEventId()).isEqualTo("e1");
        assertThat(event.update().collectedAmount()).isEqualTo(90_000);

        HttpHeaders wrong = new HttpHeaders();
        wrong.add(WebhookSigner.TIMESTAMP_HEADER, Long.toString(ts));
        wrong.add(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign("wrong-secret-wrong-secret-wrong-secret", ts, body));
        assertThatThrownBy(() -> mock.parseWebhook(wrong, body)).isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void unknownCourierStatusChangesNothingAndBrokenBodiesAre400() {
        MockCourierProvider mock = mock(MockMode.OK);
        assertThat(parse(mock, "{\"consignmentId\":\"MOCK-1\",\"status\":\"hold\"}").update().status()).isNull();
        assertThat(parse(mock, "{\"consignmentId\":\"MOCK-1\",\"status\":\"location_update\"}").update().status()).isNull();
        assertThatThrownBy(() -> parse(mock, "not json")).isInstanceOf(InvalidWebhookPayloadException.class);
        assertThatThrownBy(() -> parse(mock, "{\"consignmentId\":\"MOCK-1\"}")).isInstanceOf(InvalidWebhookPayloadException.class);
        assertThatThrownBy(() -> parse(mock, "{\"consignmentId\":\"MOCK-1\",\"status\":\"delivered\",\"collectedAmount\":2.5}"))
                .isInstanceOf(InvalidWebhookPayloadException.class);
    }

    private static WebhookEvent parse(MockCourierProvider mock, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.add(WebhookSigner.TIMESTAMP_HEADER, Long.toString(NOW.getEpochSecond()));
        headers.add(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign(SECRET, NOW.getEpochSecond(), body));
        return mock.parseWebhook(headers, body);
    }
}
