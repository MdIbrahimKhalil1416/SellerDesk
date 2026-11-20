package com.sellerdesk.product.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.product.dto.ProductResponse;
import com.sellerdesk.product.dto.ReorderImagesRequest;
import com.sellerdesk.product.service.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Product images (docs/modules/PRODUCT.md §4). Files go through the backend to Cloudinary, so the
 * backend can check type, size and count, and the Cloudinary secret never reaches the browser.
 */
@Tag(name = "Product images", description = "Upload (JPEG/PNG/WebP, max 5 MB, max 8 per product), remove, reorder")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/products/{id}/images")
@RequiredArgsConstructor
public class ProductImageController {

    private final ProductImageService imageService;

    @Operation(summary = "Upload an image",
            description = "One file per request in the multipart field `file`. The type is checked by the file's bytes, "
                    + "not by its name or Content-Type. The first image is the cover.")
    @ApiResponse(responseCode = "201", description = "Uploaded; the whole product is returned")
    @ApiResponse(responseCode = "400", description = "IMAGE_TYPE_NOT_ALLOWED (not JPEG/PNG/WebP, or empty)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "IMAGE_LIMIT_REACHED (8 images)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "IMAGE_TOO_LARGE (over 5 MB)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "502", description = "IMAGE_UPLOAD_FAILED (Cloudinary failed; try again)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ProductController.EDIT)
    public ProductResponse upload(
            @PathVariable String id,
            @Parameter(description = "JPEG, PNG or WebP, max 5 MB",
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
            @RequestPart("file") MultipartFile file) {
        return imageService.upload(id, file);
    }

    @Operation(summary = "Remove an image", description = "The file is also deleted from Cloudinary.")
    @ApiResponse(responseCode = "200", description = "Removed; the whole product is returned")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (product or image)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/{imageId}")
    @PreAuthorize(ProductController.EDIT)
    public ProductResponse delete(@PathVariable String id, @PathVariable String imageId) {
        return imageService.delete(id, imageId);
    }

    @Operation(summary = "Reorder images", description = "Same set of image ids in a new order; the first becomes the cover.")
    @ApiResponse(responseCode = "200", description = "Reordered")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (ids are not exactly the product's images)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/order")
    @PreAuthorize(ProductController.EDIT)
    public ProductResponse reorder(@PathVariable String id, @Valid @RequestBody ReorderImagesRequest request) {
        return imageService.reorder(id, request);
    }
}
