package com.sellerdesk.shop;

import static com.sellerdesk.shop.ShopTestApi.update;
import static com.sellerdesk.shop.ShopTestApi.updateJson;
import static com.sellerdesk.shop.ShopTestApi.uploadLogo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerdesk.common.security.Permission;
import com.sellerdesk.common.security.Role;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import com.sellerdesk.testsupport.TestImages;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * Every shop write needs {@code SHOP_SETTINGS}. Only the owner has it by default
 * (docs/SECURITY.md §3); staff without it get {@code 403 FORBIDDEN} and nothing changes.
 */
class ShopPermissionTest extends MongoIntegrationTestBase {

    private TestOwner owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = createVerifiedOwner("shop-perm");
    }

    private List<AbstractMockHttpServletRequestBuilder<?>> writeRequests() {
        return List.of(
                update(updateJson("Hacked", "01712345678", "HACK")),
                uploadLogo(TestImages.png()),
                delete("/api/v1/shop/logo"));
    }

    @Test
    void moderatorGets403OnEveryWriteAndNothingChanges() throws Exception {
        String moderator = bearerForStaff(owner.shopId(), Role.MODERATOR);
        Document before = storedShop();

        for (AbstractMockHttpServletRequestBuilder<?> request : writeRequests()) {
            mockMvc.perform(request.header("Authorization", moderator))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        assertThat(storedShop()).isEqualTo(before);
        assertThat(imageStorage.uploads()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MODERATOR", "PACKER", "ACCOUNTANT"})
    void noStaffRoleHasShopSettingsByDefault(Role role) throws Exception {
        assertThat(role.defaultPermissions()).doesNotContain(Permission.SHOP_SETTINGS);
        mockMvc.perform(update(updateJson("Hacked", "01712345678", "HACK"))
                        .header("Authorization", bearerForStaff(owner.shopId(), role)))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCanStillReadTheShop() throws Exception {
        mockMvc.perform(get("/api/v1/shop").header("Authorization", bearerForStaff(owner.shopId(), Role.MODERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.orderNumberPrefix").value("SD"));
    }

    @Test
    void staffGrantedShopSettingsMayChangeIt() throws Exception {
        String manager = bearerForStaff(owner.shopId(), Role.MODERATOR, EnumSet.of(Permission.SHOP_SETTINGS));

        mockMvc.perform(update(updateJson("Managed", "01712345678", "MG")).header("Authorization", manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Managed"));
    }

    @Test
    void ownerHasShopSettingsByDefault() throws Exception {
        assertThat(Role.SHOP_OWNER.defaultPermissions()).contains(Permission.SHOP_SETTINGS);
        mockMvc.perform(update(updateJson("Owner Edit", "01712345678", "OE")).header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminHasNoShopAndGets403() throws Exception {
        String admin = bearerForStaff(null, Role.PLATFORM_ADMIN, Set.of());
        for (AbstractMockHttpServletRequestBuilder<?> request : writeRequests()) {
            mockMvc.perform(request.header("Authorization", admin)).andExpect(status().isForbidden());
        }
    }

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(update(updateJson("X1", "01712345678", "SD"))).andExpect(status().isUnauthorized());
        mockMvc.perform(uploadLogo(TestImages.png())).andExpect(status().isUnauthorized());
    }

    private Document storedShop() {
        return mongoTemplate.findById(new ObjectId(owner.shopId()), Document.class, "shops");
    }
}
