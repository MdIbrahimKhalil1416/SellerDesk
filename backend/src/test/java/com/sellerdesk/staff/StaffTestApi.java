package com.sellerdesk.staff;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Staff API calls and the invite email, shared by the staff integration tests and TenantIsolationTest. */
public class StaffTestApi {

    public static final String STAFF_PASSWORD = "staffPass2026";
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    private final MockMvc mockMvc;
    private final JavaMailSender mailSender;

    public StaffTestApi(MockMvc mockMvc, JavaMailSender mailSender) {
        this.mockMvc = mockMvc;
        this.mailSender = mailSender;
    }

    /** {@code POST /staff/invitations} → 201; returns the invitation id. */
    public String invite(String bearer, String email, String role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/invitations").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"role\":\"%s\"}".formatted(email, role)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** Waits (max 10 s) for the newest invite email to {@code email} whose token differs from {@code notThis}. */
    public String awaitInviteToken(String email, String notThis) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<String> token = Mockito.mockingDetails(mailSender).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("send") && i.getArguments().length == 1
                            && i.getArgument(0) instanceof SimpleMailMessage)
                    .map(i -> (SimpleMailMessage) i.getArgument(0))
                    .filter(m -> m.getTo() != null && email.equals(m.getTo()[0]))
                    .map(m -> {
                        Matcher matcher = TOKEN.matcher(Objects.requireNonNullElse(m.getText(), ""));
                        return matcher.find() ? matcher.group(1) : null;
                    })
                    .filter(Objects::nonNull)
                    .reduce((first, second) -> second);
            if (token.isPresent() && !token.get().equals(notThis)) {
                return token.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No invite email was sent to " + email + " within 10 s");
    }

    public String awaitInviteToken(String email) throws InterruptedException {
        return awaitInviteToken(email, null);
    }

    /** {@code POST /auth/invitations/accept} → 200; returns the response. */
    public MvcResult accept(String token, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"name\":\"%s\",\"password\":\"%s\"}".formatted(token, name, STAFF_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** Invite + accept: a real ACTIVE member. Returns their access token. */
    public Member inviteAndAccept(String bearer, String email, String role) throws Exception {
        String invitationId = invite(bearer, email, role);
        MvcResult accepted = accept(awaitInviteToken(email), "Member " + role);
        String body = accepted.getResponse().getContentAsString();
        return new Member(JsonPath.read(body, "$.user.id"), email, JsonPath.read(body, "$.accessToken"),
                invitationId, accepted.getResponse().getCookie("sd_refresh"));
    }

    public record Member(String userId, String email, String accessToken, String invitationId,
                         jakarta.servlet.http.Cookie refreshCookie) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }
}
