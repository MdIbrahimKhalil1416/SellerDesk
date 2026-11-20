package com.sellerdesk.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.security.Permission;
import com.sellerdesk.product.dto.ProductLookupItem;
import com.sellerdesk.product.dto.ProductResponse;
import com.sellerdesk.product.dto.ProductSummary;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.ProductImage;
import com.sellerdesk.product.model.ProductStatus;
import com.sellerdesk.product.model.Variant;
import com.sellerdesk.product.model.VariantStock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductMapperTest {

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ProductMapper mapper = new ProductMapper(currentUser);

    private void givenPermissions(Permission... permissions) {
        Set<Permission> granted = permissions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.of(permissions[0], permissions);
        for (Permission p : Permission.values()) {
            when(currentUser.hasPermission(p)).thenReturn(granted.contains(p));
        }
    }

    @Test
    void costPriceAndStockHiddenWithoutPermissions() {
        givenPermissions(Permission.PRODUCT_VIEW);

        ProductResponse.VariantResponse v = mapper.toResponse(product()).variants().getFirst();

        assertThat(v.costPrice()).isNull();
        assertThat(v.stock()).isNull();
        assertThat(v.price()).isEqualTo(45_050);
    }

    @Test
    void costPriceShownWithProductEdit() {
        givenPermissions(Permission.PRODUCT_VIEW, Permission.PRODUCT_EDIT);
        assertThat(mapper.toResponse(product()).variants().getFirst().costPrice()).isEqualTo(30_000);
    }

    @Test
    void costPriceShownWithReportView() {
        givenPermissions(Permission.PRODUCT_VIEW, Permission.REPORT_VIEW);
        assertThat(mapper.toResponse(product()).variants().getFirst().costPrice()).isEqualTo(30_000);
    }

    @Test
    void stockShownWithStockView() {
        givenPermissions(Permission.PRODUCT_VIEW, Permission.STOCK_VIEW);
        ProductResponse.StockResponse stock = mapper.toResponse(product()).variants().getFirst().stock();
        assertThat(stock).isEqualTo(new ProductResponse.StockResponse(7, 2, 5));
    }

    @Test
    void responseNeverContainsPublicIdAndKeepsAttributeOrder() {
        givenPermissions(Permission.PRODUCT_VIEW);
        ProductResponse response = mapper.toResponse(product());

        assertThat(response.images().getFirst().url()).isEqualTo("https://img/cover.jpg");
        assertThat(response.toString()).doesNotContain("sellerdesk/shopA/products");
        assertThat(response.variants().getFirst().attributes().keySet()).containsExactly("Color", "Size");
    }

    @Test
    void summaryTotalAvailableOnlyWithStockView() {
        givenPermissions(Permission.PRODUCT_VIEW);
        assertThat(mapper.toSummary(product()).totalAvailable()).isNull();

        givenPermissions(Permission.PRODUCT_VIEW, Permission.STOCK_VIEW);
        ProductSummary summary = mapper.toSummary(product());
        assertThat(summary.totalAvailable()).isEqualTo(5 + 1);
        assertThat(summary.variantCount()).isEqualTo(2);
        assertThat(summary.priceRange()).isEqualTo(new ProductSummary.PriceRange(45_050L, 45_050L));
        assertThat(summary.coverImageUrl()).isEqualTo("https://img/cover.jpg");
    }

    @Test
    void lookupHasOnlyActiveVariantsAndNothingPermissionDependent() {
        Optional<ProductLookupItem> item = mapper.toLookupItem(product());

        assertThat(item).isPresent();
        assertThat(item.get().variants()).extracting(ProductLookupItem.LookupVariant::sku).containsExactly("TS-RED-M");
        assertThat(item.get().coverImageUrl()).isEqualTo("https://img/cover.jpg");
    }

    @Test
    void lookupSkipsProductWithoutActiveVariants() {
        Product product = product();
        product.getVariants().forEach(v -> v.setActive(false));
        assertThat(mapper.toLookupItem(product)).isEmpty();
    }

    static Product product() {
        Product p = new Product();
        p.setId("p1");
        p.setShopId("shopA");
        p.setName("T-Shirt");
        p.setStatus(ProductStatus.ACTIVE);
        p.setMinPrice(45_050L);
        p.setMaxPrice(45_050L);
        p.setVersion(3L);

        ProductImage image = new ProductImage();
        image.setImageId("i1");
        image.setUrl("https://img/cover.jpg");
        image.setPublicId("sellerdesk/shopA/products/i1");
        p.setImages(List.of(image));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Color", "Red");
        attributes.put("Size", "M");
        Variant active = new Variant();
        active.setVariantId("v1");
        active.setSku("TS-RED-M");
        active.setName("Red / M");
        active.setAttributes(attributes);
        active.setPrice(45_050);
        active.setCostPrice(30_000L);
        active.setActive(true);
        active.setStock(new VariantStock(7, 2, 5));

        Variant inactive = new Variant();
        inactive.setVariantId("v2");
        inactive.setSku("TS-OLD");
        inactive.setName("Old");
        inactive.setPrice(100);
        inactive.setActive(false);
        inactive.setStock(new VariantStock(1, 0, 1));
        p.setVariants(List.of(active, inactive));
        return p;
    }
}
