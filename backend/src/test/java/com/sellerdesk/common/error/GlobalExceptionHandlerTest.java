package com.sellerdesk.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit test for {@link GlobalExceptionHandler}. No Spring Boot context and no database:
 * a tiny fake controller throws each kind of exception and we check the JSON that comes out.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FakeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validationErrorReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/fake/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/fake/validate"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'quantity')]").exists());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/fake/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void resourceNotFoundReturns404WithDerivedCode() throws Exception {
        mockMvc.perform(get("/fake/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_MOVEMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Stock movement not found"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(0));
    }

    @Test
    void businessRuleReturns409ByDefault() throws Exception {
        mockMvc.perform(get("/fake/business-rule"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("Not enough stock"));
    }

    @Test
    void accessDeniedReturns403() throws Exception {
        mockMvc.perform(get("/fake/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void duplicateKeyReturns409() throws Exception {
        mockMvc.perform(get("/fake/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_KEY"));
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/fake/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Something went wrong. Please try again later"));
    }

    @Test
    void wrongHttpMethodReturns405() throws Exception {
        mockMvc.perform(post("/fake/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void uploadOverMultipartLimitReturns413ImageTooLarge() throws Exception {
        mockMvc.perform(get("/fake/upload-too-large"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    @Test
    void appExceptionWithFieldErrorsKeepsThemAlsoForNon400() throws Exception {
        mockMvc.perform(get("/fake/conflict-with-field"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("variants[2].sku"));
    }

    @Test
    void fieldValidationExceptionReturns400WithItsField() throws Exception {
        mockMvc.perform(get("/fake/field-rule"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    // ---- Fake controller used only by this test ----

    static class FakeConflict extends AppException {
        FakeConflict() {
            super(org.springframework.http.HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS", "taken",
                    java.util.List.of(new ApiError.FieldError("variants[2].sku", "SKU X already exists")));
        }
    }

    record FakeRequest(@NotBlank String name, @Min(1) int quantity) {
    }

    @RestController
    static class FakeController {

        @PostMapping("/fake/validate")
        String validate(@Valid @RequestBody FakeRequest request) {
            return "ok";
        }

        @GetMapping("/fake/not-found")
        String notFound() {
            throw ResourceNotFoundException.of("Stock movement");
        }

        @GetMapping("/fake/business-rule")
        String businessRule() {
            throw new BusinessRuleException("INSUFFICIENT_STOCK", "Not enough stock");
        }

        @GetMapping("/fake/access-denied")
        String accessDenied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/fake/duplicate")
        String duplicate() {
            throw new DuplicateKeyException("E11000 duplicate key error: secret internal details");
        }

        @GetMapping("/fake/upload-too-large")
        String uploadTooLarge() {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(5 * 1024 * 1024);
        }

        @GetMapping("/fake/conflict-with-field")
        String conflictWithField() {
            throw new FakeConflict();
        }

        @GetMapping("/fake/field-rule")
        String fieldRule() {
            throw new FieldValidationException("sort", "unknown sort");
        }

        @GetMapping("/fake/boom")
        String boom() {
            throw new IllegalStateException("database password is hunter2");
        }
    }
}
