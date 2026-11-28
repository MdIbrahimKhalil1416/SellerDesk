package com.sellerdesk.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.customer.dto.AddressRequest;
import com.sellerdesk.customer.dto.CreateCustomerRequest;
import com.sellerdesk.customer.dto.CustomerResponse;
import com.sellerdesk.customer.dto.UpdateCustomerRequest;
import com.sellerdesk.customer.model.Customer;
import com.sellerdesk.customer.model.CustomerAddress;
import com.sellerdesk.customer.model.CustomerStats;
import com.sellerdesk.customer.model.CustomerStatus;
import com.sellerdesk.customer.repository.CustomerQueryRepository;
import com.sellerdesk.customer.repository.CustomerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Business rules of {@link CustomerService} with mocked repositories. The shop always comes from
 * {@link CurrentUser}; every repository call must receive exactly that shop.
 */
class CustomerServiceTest {

    private static final String SHOP = new ObjectId().toHexString();
    private static final String USER = new ObjectId().toHexString();
    private static final String ID = new ObjectId().toHexString();
    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

    private final CustomerRepository repository = mock(CustomerRepository.class);
    private final CustomerQueryRepository queryRepository = mock(CustomerQueryRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final CustomerService service = new CustomerService(repository, queryRepository, currentUser,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void loggedInAsShopA() {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(currentUser.userId()).thenReturn(USER);
        when(repository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(ID);
            c.setVersion(0L);
            return c;
        });
        // The address documents themselves are tested against a real database.
        when(queryRepository.addressDocuments(any())).thenAnswer(inv -> {
            List<CustomerAddress> list = inv.getArgument(0);
            return list.stream().map(a -> new Document("addressId", a.getAddressId()).append("isDefault", a.isDefault())).toList();
        });
    }

    private static CreateCustomerRequest create(String phone, String altPhone, List<AddressRequest> addresses) {
        return new CreateCustomerRequest("  Rahima   Begum ", phone, altPhone, null, " likes red ", List.of("VIP", "vip"), addresses);
    }

    private static AddressRequest address(String line, Boolean isDefault) {
        return new AddressRequest("Home", line, "Mirpur", "Dhaka", DeliveryZone.INSIDE_DHAKA, null, isDefault);
    }

    private static Customer stored(CustomerStatus status, CustomerAddress... addresses) {
        Customer c = new Customer();
        c.setId(ID);
        c.setShopId(SHOP);
        c.setName("Rahima Begum");
        c.setPhone("01712345678");
        c.setStatus(status);
        c.setStats(CustomerStats.empty());
        c.setAddresses(new ArrayList<>(List.of(addresses)));
        c.setAddressRevision(3);
        c.setVersion(2L);
        return c;
    }

    private static CustomerAddress saved(String line, boolean isDefault) {
        CustomerAddress a = new CustomerAddress();
        a.setAddressId(new ObjectId().toHexString());
        a.setLine(line);
        a.setArea("Mirpur");
        a.setDistrict("Dhaka");
        a.setZone(DeliveryZone.INSIDE_DHAKA);
        a.setDefault(isDefault);
        return a;
    }

    // ==== Phone normalization ============================================================

    @Nested
    class Phones {

        @ParameterizedTest(name = "{0} is stored as 01712345678")
        @ValueSource(strings = {"01712345678", "+8801712345678", "8801712345678", "008801712345678",
                "01712-345 678", "(017) 1234.5678", "০১৭১২৩৪৫৬৭৮"})
        void everyAcceptedSpellingIsStoredTheSameWay(String phone) {
            CustomerResponse response = service.create(create(phone, null, null));

            assertThat(response.phone()).isEqualTo("01712345678");
            verify(queryRepository).phoneTaken(SHOP, "01712345678", null);
            ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getPhoneKeys()).containsExactly("01712345678");
            assertThat(saved.getValue().getPhoneKeysReversed()).containsExactly("87654321710");
        }

        @ParameterizedTest
        @ValueSource(strings = {"01212345678", "0171234567", "017123456789", "+1 202 555 0100", "abc"})
        void notABangladeshiMobileIs400(String phone) {
            assertThatThrownBy(() -> service.create(create(phone, null, null))).isInstanceOf(FieldValidationException.class);
            assertThatThrownBy(() -> service.lookup(phone)).isInstanceOf(FieldValidationException.class);
            verify(repository, never()).save(any());
        }

        @Test
        void lookupNormalizesBeforeSearching() {
            when(repository.findByShopIdAndPhone(SHOP, "01712345678")).thenReturn(Optional.of(stored(CustomerStatus.ARCHIVED)));
            assertThat(service.lookup("+880 1712-345678").status()).isEqualTo(CustomerStatus.ARCHIVED);
            when(repository.findByShopIdAndPhone(SHOP, "01812345678")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.lookup("01812345678")).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void altPhoneIsNormalizedAndMustDifferFromPhone() {
            assertThat(service.create(create("01712345678", "+880 1812-345678", null)).altPhone()).isEqualTo("01812345678");
            assertThatThrownBy(() -> service.create(create("01712345678", "+8801712345678", null)))
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("altPhone"));
        }

        @Test
        void duplicatePhoneIs409FromPreCheckOrUniqueIndex() {
            when(queryRepository.phoneTaken(SHOP, "01712345678", null)).thenReturn(true);
            assertThatThrownBy(() -> service.create(create("+8801712345678", null, null)))
                    .extracting("code").isEqualTo(CustomerErrors.PHONE_ALREADY_EXISTS);

            when(queryRepository.phoneTaken(SHOP, "01712345678", null)).thenReturn(false);
            when(repository.save(any())).thenThrow(new DuplicateKeyException("E11000"));
            assertThatThrownBy(() -> service.create(create("01712345678", null, null)))
                    .isInstanceOf(AppException.class)
                    .extracting("code").isEqualTo(CustomerErrors.PHONE_ALREADY_EXISTS);
        }

        @Test
        void phoneChangeToATakenPhoneIs409ExcludingTheCustomerItself() {
            when(repository.existsByIdAndShopId(ID, SHOP)).thenReturn(true);
            when(queryRepository.phoneTaken(SHOP, "01812345678", ID)).thenReturn(true);
            assertThatThrownBy(() -> service.update(ID,
                    new UpdateCustomerRequest("R", "01812345678", null, null, null, null, 2L)))
                    .extracting("code").isEqualTo(CustomerErrors.PHONE_ALREADY_EXISTS);
        }
    }

    // ==== Create, update =================================================================

    @Nested
    class CreateAndUpdate {

        @Test
        void createNormalizesTextAndTagsAndMakesFirstAddressDefault() {
            CustomerResponse response = service.create(create("01712345678", null,
                    List.of(address("House 1", null), address("House 2", null))));

            assertThat(response.name()).isEqualTo("Rahima Begum");
            assertThat(response.note()).isEqualTo("likes red");
            assertThat(response.tags()).containsExactly("VIP");
            assertThat(response.status()).isEqualTo(CustomerStatus.ACTIVE);
            assertThat(response.addresses()).extracting(CustomerResponse.AddressResponse::isDefault).containsExactly(true, false);
            assertThat(response.addresses()).extracting(CustomerResponse.AddressResponse::addressId).doesNotContainNull();
            ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getShopId()).isEqualTo(SHOP);
            assertThat(saved.getValue().getCreatedBy()).isEqualTo(USER);
            assertThat(saved.getValue().getTagKeys()).containsExactly("vip");
        }

        @Test
        void createWithAnExplicitDefault() {
            CustomerResponse response = service.create(create("01712345678", null,
                    List.of(address("House 1", null), address("House 2", true))));
            assertThat(response.addresses()).extracting(CustomerResponse.AddressResponse::isDefault).containsExactly(false, true);
        }

        @Test
        void updateIsATargetedVersionedSetThatClearsLeftOutOptionalFields() {
            when(repository.existsByIdAndShopId(ID, SHOP)).thenReturn(true);
            when(queryRepository.modifyVersioned(eq(SHOP), eq(ID), any(), any())).thenReturn(Optional.of(stored(CustomerStatus.ACTIVE)));
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);

            service.update(ID, new UpdateCustomerRequest("Rahima", "01712345678", null, null, null, List.of("A"), 2L));

            verify(queryRepository).modifyVersioned(eq(SHOP), eq(ID), condition.capture(), update.capture());
            assertThat(condition.getValue().getCriteriaObject()).isEqualTo(new Document("version", 2L));
            Document u = update.getValue().getUpdateObject();
            assertThat(u.get("$inc", Document.class)).containsEntry("version", 1);
            assertThat(u.get("$unset", Document.class)).containsKeys("altPhone", "facebookProfileUrl", "note");
            assertThat(u.get("$set", Document.class)).doesNotContainKeys("stats", "addresses", "status", "shopId");
        }

        @Test
        void staleVersionIs409AndUnknownIs404() {
            when(queryRepository.modifyVersioned(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(repository.existsByIdAndShopId(ID, SHOP)).thenReturn(true);
            UpdateCustomerRequest request = new UpdateCustomerRequest("R", "01712345678", null, null, null, null, 1L);
            assertThatThrownBy(() -> service.update(ID, request)).isInstanceOf(OptimisticLockingFailureException.class);

            when(repository.existsByIdAndShopId(ID, SHOP)).thenReturn(false);
            assertThatThrownBy(() -> service.update(ID, request)).isInstanceOf(ResourceNotFoundException.class);
            // Unknown id with a taken phone: still 404, the phone check never runs.
            when(queryRepository.phoneTaken(any(), any(), any())).thenReturn(true);
            assertThatThrownBy(() -> service.update(ID, request)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.get("not-an-id")).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ==== Addresses ======================================================================

    @Nested
    class Addresses {

        @Test
        void addIsACompareAndSetOnTheAddressRevision() {
            when(repository.findByIdAndShopId(ID, SHOP)).thenReturn(Optional.of(stored(CustomerStatus.ACTIVE, saved("House 1", true))));
            when(queryRepository.modifyUnversioned(eq(SHOP), eq(ID), any(), any())).thenReturn(Optional.of(stored(CustomerStatus.ACTIVE)));
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);

            service.addAddress(ID, address("House 2", true));

            verify(queryRepository).modifyUnversioned(eq(SHOP), eq(ID), condition.capture(), update.capture());
            assertThat(condition.getValue().getCriteriaObject()).isEqualTo(new Document("addressRevision", 3));
            Document u = update.getValue().getUpdateObject();
            assertThat(u.get("$inc", Document.class)).containsEntry("addressRevision", 1).doesNotContainKey("version");
            @SuppressWarnings("unchecked")
            List<Document> written = (List<Document>) u.get("$set", Document.class).get("addresses");
            assertThat(written).extracting(d -> d.getBoolean("isDefault")).containsExactly(false, true);
        }

        @Test
        void sixthAddressIs409() {
            CustomerAddress[] five = {saved("1", true), saved("2", false), saved("3", false), saved("4", false), saved("5", false)};
            when(repository.findByIdAndShopId(ID, SHOP)).thenReturn(Optional.of(stored(CustomerStatus.ACTIVE, five)));
            assertThatThrownBy(() -> service.addAddress(ID, address("6", null)))
                    .extracting("code").isEqualTo(CustomerErrors.ADDRESS_LIMIT_REACHED);
            verify(queryRepository, never()).modifyUnversioned(any(), any(), any(), any());
        }

        @Test
        void lostRaceIsRetriedWithFreshData() {
            when(repository.findByIdAndShopId(ID, SHOP)).thenReturn(Optional.of(stored(CustomerStatus.ACTIVE)));
            when(queryRepository.modifyUnversioned(eq(SHOP), eq(ID), any(), any()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(stored(CustomerStatus.ACTIVE)));
            service.addAddress(ID, address("House 1", null));
            verify(repository, org.mockito.Mockito.times(2)).findByIdAndShopId(ID, SHOP);
        }

        @Test
        void unknownAddressIs404() {
            when(repository.findByIdAndShopId(ID, SHOP)).thenReturn(Optional.of(stored(CustomerStatus.ACTIVE, saved("House 1", true))));
            assertThatThrownBy(() -> service.removeAddress(ID, new ObjectId().toHexString()))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessage("Address not found");
        }

        @Test
        void defaultRules() {
            CustomerAddress a = saved("A", false);
            CustomerAddress b = saved("B", true);
            CustomerAddress c = saved("C", false);
            // current default kept
            assertThat(CustomerService.withDefault(new ArrayList<>(List.of(a, b, c)), null))
                    .extracting(CustomerAddress::isDefault).containsExactly(false, true, false);
            // preferred moves it
            assertThat(CustomerService.withDefault(new ArrayList<>(List.of(a, b, c)), c.getAddressId()))
                    .extracting(CustomerAddress::isDefault).containsExactly(false, false, true);
            // default removed: the oldest remaining becomes default
            a.setDefault(false);
            c.setDefault(false);
            assertThat(CustomerService.withDefault(new ArrayList<>(List.of(a, c)), null))
                    .extracting(CustomerAddress::isDefault).containsExactly(true, false);
        }
    }

    // ==== Order API ======================================================================

    @Nested
    class FindOrCreate {

        private final AddressDto delivery = new AddressDto("House 9", "Uttara", "Dhaka", DeliveryZone.INSIDE_DHAKA, null);

        @Test
        void newPhoneCreatesACustomerWithTheAddressAsDefault() {
            when(repository.findByShopIdAndPhone(SHOP, "01712345678")).thenReturn(Optional.empty());

            OrderCustomer result = service.findOrCreateByPhone(new NewOrderCustomer(" Karim ", "+8801712345678", delivery));

            assertThat(result.customerId()).isEqualTo(ID);
            assertThat(result.name()).isEqualTo("Karim");
            assertThat(result.phone()).isEqualTo("01712345678");
            assertThat(result.defaultAddress().line()).isEqualTo("House 9");
            assertThat(result.defaultAddress().isDefault()).isTrue();
        }

        @Test
        void existingCustomerKeepsItsNameAndGetsTheNewAddress() {
            Customer existing = stored(CustomerStatus.ACTIVE, saved("House 1", true));
            when(repository.findByShopIdAndPhone(SHOP, "01712345678")).thenReturn(Optional.of(existing));
            when(repository.findByIdAndShopId(ID, SHOP)).thenReturn(Optional.of(existing));
            when(queryRepository.modifyUnversioned(eq(SHOP), eq(ID), any(), any())).thenReturn(Optional.of(existing));
            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);

            OrderCustomer result = service.findOrCreateByPhone(new NewOrderCustomer("Somebody Else", "01712345678", delivery));

            assertThat(result.name()).isEqualTo("Rahima Begum");
            verify(repository, never()).save(any());
            verify(queryRepository).modifyUnversioned(eq(SHOP), eq(ID), any(), update.capture());
            @SuppressWarnings("unchecked")
            List<Document> written = (List<Document>) update.getValue().getUpdateObject().get("$set", Document.class).get("addresses");
            assertThat(written).hasSize(2);
            assertThat(update.getValue().getUpdateObject().get("$set", Document.class)).doesNotContainKey("name");
        }

        @Test
        void knownAddressOrFullListChangesNothing() {
            Customer existing = stored(CustomerStatus.ACTIVE, saved("house 9", true));
            existing.getAddresses().get(0).setArea("UTTARA");
            when(repository.findByShopIdAndPhone(SHOP, "01712345678")).thenReturn(Optional.of(existing));
            when(repository.findByIdAndShopId(ID, SHOP)).thenReturn(Optional.of(existing));

            service.findOrCreateByPhone(new NewOrderCustomer("X", "01712345678", delivery));

            verify(queryRepository, never()).modifyUnversioned(any(), any(), any(), any());
        }

        @Test
        void archivedCustomerIsReactivated() {
            Customer archived = stored(CustomerStatus.ARCHIVED);
            when(repository.findByShopIdAndPhone(SHOP, "01712345678")).thenReturn(Optional.of(archived));
            when(queryRepository.modifyUnversioned(eq(SHOP), eq(ID), isNull(), any())).thenReturn(Optional.of(stored(CustomerStatus.ACTIVE)));
            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);

            assertThat(service.findOrCreateByPhone(new NewOrderCustomer("X", "01712345678", null)).status())
                    .isEqualTo(CustomerStatus.ACTIVE);
            verify(queryRepository).modifyUnversioned(eq(SHOP), eq(ID), isNull(), update.capture());
            assertThat(update.getValue().getUpdateObject().get("$set", Document.class)).containsEntry("status", CustomerStatus.ACTIVE);
        }

        @Test
        void parallelCreateOfTheSamePhoneUsesTheWinner() {
            when(repository.findByShopIdAndPhone(SHOP, "01712345678"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(stored(CustomerStatus.ACTIVE)));
            when(repository.save(any())).thenThrow(new DuplicateKeyException("E11000"));

            assertThat(service.findOrCreateByPhone(new NewOrderCustomer("Karim", "01712345678", null)).name())
                    .isEqualTo("Rahima Begum");
        }

        @Test
        void invalidPhoneOrMissingNameForANewCustomerIs400WithOrderFieldNames() {
            assertThatThrownBy(() -> service.findOrCreateByPhone(new NewOrderCustomer("X", "123", null)))
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("customer.phone"));
            when(repository.findByShopIdAndPhone(anyString(), anyString())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.findOrCreateByPhone(new NewOrderCustomer("  ", "01712345678", null)))
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("customer.name"));
        }

        @Test
        void countersAreIncrementsAndUnknownCustomerIs404() {
            when(queryRepository.modifyUnversioned(eq(SHOP), eq(ID), isNull(), any())).thenReturn(Optional.of(stored(CustomerStatus.ACTIVE)));
            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);

            service.recordDelivered(ID, 45_000L);

            verify(queryRepository).modifyUnversioned(eq(SHOP), eq(ID), isNull(), update.capture());
            assertThat(update.getValue().getUpdateObject().get("$inc", Document.class))
                    .containsEntry("stats.deliveredOrders", 1).containsEntry("stats.totalSpent", 45_000L);

            String other = new ObjectId().toHexString();
            when(queryRepository.modifyUnversioned(eq(SHOP), eq(other), isNull(), any())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.recordCancelled(other)).isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
