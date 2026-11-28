package com.sellerdesk.customer.service;

import com.sellerdesk.common.dto.AddressDto;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.phone.BdPhone;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.customer.dto.AddressRequest;
import com.sellerdesk.customer.dto.CreateCustomerRequest;
import com.sellerdesk.customer.dto.CustomerResponse;
import com.sellerdesk.customer.dto.CustomerStatusRequest;
import com.sellerdesk.customer.dto.CustomerSummary;
import com.sellerdesk.customer.dto.TagCount;
import com.sellerdesk.customer.dto.UpdateCustomerRequest;
import com.sellerdesk.customer.model.Customer;
import com.sellerdesk.customer.model.CustomerAddress;
import com.sellerdesk.customer.model.CustomerStats;
import com.sellerdesk.customer.model.CustomerStatus;
import com.sellerdesk.customer.repository.CustomerQueryRepository;
import com.sellerdesk.customer.repository.CustomerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers of a shop (docs/modules/CUSTOMER.md).
 *
 * <p>The shop always comes from {@link CurrentUser#shopId()} (the JWT), never from the request.
 * A customer or address of another shop is "not found" (404).
 *
 * <p>After create, nothing is saved as a whole document: profile and status are targeted updates
 * with {@code version == loaded version}; addresses are replaced as one array with a
 * compare-and-set on {@code addressRevision}; Order's counters are {@code $inc}. So the counters
 * can never be overwritten by an edit made at the same moment.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    /** Retries of an address change that lost a race with another address change. */
    private static final int ADDRESS_ATTEMPTS = 5;

    private final CustomerRepository customerRepository;
    private final CustomerQueryRepository queryRepository;
    private final CurrentUser currentUser;
    private final Clock clock;

    // ==== Read ===========================================================================

    /**
     * @param status {@code ACTIVE} (default), {@code ARCHIVED} or {@code ALL}
     * @param sort   {@code createdAt,desc} (default), {@code name,asc} or {@code lastOrderAt,desc}
     */
    public PageResponse<CustomerSummary> list(String q, String tag, String status, String sort, int page, int size) {
        String shopId = currentUser.shopId();
        CustomerRules.SearchText text = CustomerRules.parseSearch(q);
        String phone = text == null ? null : text.phoneDigits();
        CustomerQueryRepository.Search search = new CustomerQueryRepository.Search(
                phone == null ? null : CustomerRules.startsWithRegex(phone),
                phone != null && text.suffix() ? CustomerRules.startsWithRegex(CustomerRules.reverse(phone)) : null,
                text == null || text.nameLower() == null ? null : CustomerRules.startsWithRegex(text.nameLower()),
                CustomerRules.lower(CustomerRules.normalizeText(tag)),
                parseStatusFilter(status), parseSort(sort), page, size);
        CustomerQueryRepository.SearchResult result = queryRepository.search(shopId, search);
        return PageResponse.of(result.content().stream().map(CustomerMapper::toSummary).toList(),
                page, size, result.totalElements());
    }

    public List<TagCount> tags() {
        return queryRepository.tags(currentUser.shopId()).stream()
                .map(t -> new TagCount(t.name(), t.customerCount()))
                .toList();
    }

    /** Exact match on the main phone (any accepted format). Archived customers are found too. */
    public CustomerResponse lookup(String phone) {
        String shopId = currentUser.shopId();
        String normalized = BdPhone.tryNormalize(phone)
                .orElseThrow(() -> new FieldValidationException("phone", BdPhone.INVALID_MESSAGE));
        return customerRepository.findByShopIdAndPhone(shopId, normalized)
                .map(CustomerMapper::toResponse)
                .orElseThrow(CustomerErrors::customerNotFound);
    }

    public CustomerResponse get(String customerId) {
        return CustomerMapper.toResponse(load(currentUser.shopId(), customerId));
    }

    // ==== Create / update / status =======================================================

    public CustomerResponse create(CreateCustomerRequest request) {
        String shopId = currentUser.shopId();
        String phone = normalizePhone(request.phone(), "phone");
        String altPhone = normalizeAltPhone(request.altPhone(), phone);
        if (queryRepository.phoneTaken(shopId, phone, null)) {
            throw CustomerErrors.phoneAlreadyExists("phone", phone);
        }

        Customer customer = new Customer();
        customer.setShopId(shopId);
        applyProfile(customer, request.name(), phone, altPhone, request.facebookProfileUrl(), request.note(),
                request.tags());
        List<CustomerAddress> addresses = new ArrayList<>();
        String preferred = null;
        for (AddressRequest a : request.addresses() == null ? List.<AddressRequest>of() : request.addresses()) {
            CustomerAddress address = newAddress(a.label(), a.line(), a.area(), a.district(), a.zone(), a.note());
            if (Boolean.TRUE.equals(a.isDefault()) && preferred == null) {
                preferred = address.getAddressId();
            }
            addresses.add(address);
        }
        customer.setAddresses(withDefault(addresses, preferred));
        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setStats(CustomerStats.empty());
        customer.setCreatedBy(currentUser.userId());
        try {
            return CustomerMapper.toResponse(customerRepository.save(customer));
        } catch (DuplicateKeyException e) {
            // A parallel request created the same phone after our pre-check; the unique index decided.
            throw CustomerErrors.phoneAlreadyExists("phone", phone);
        }
    }

    /** Replaces the profile fields; addresses and counters stay as they are. */
    public CustomerResponse update(String customerId, UpdateCustomerRequest request) {
        String shopId = currentUser.shopId();
        requireValidId(customerId);
        // 404 first: an unknown id (or one of another shop) must never reveal whether a phone exists.
        if (!customerRepository.existsByIdAndShopId(customerId, shopId)) {
            throw CustomerErrors.customerNotFound();
        }
        String phone = normalizePhone(request.phone(), "phone");
        String altPhone = normalizeAltPhone(request.altPhone(), phone);
        if (queryRepository.phoneTaken(shopId, phone, customerId)) {
            throw CustomerErrors.phoneAlreadyExists("phone", phone);
        }
        Customer changed = new Customer();
        applyProfile(changed, request.name(), phone, altPhone, request.facebookProfileUrl(), request.note(),
                request.tags());
        Update update = new Update()
                .set("name", changed.getName())
                .set("nameLower", changed.getNameLower())
                .set("phone", changed.getPhone())
                .set("phoneKeys", changed.getPhoneKeys())
                .set("phoneKeysReversed", changed.getPhoneKeysReversed())
                .set("tags", changed.getTags())
                .set("tagKeys", changed.getTagKeys());
        setOrUnset(update, "altPhone", changed.getAltPhone());
        setOrUnset(update, "facebookProfileUrl", changed.getFacebookProfileUrl());
        setOrUnset(update, "note", changed.getNote());
        try {
            return applyVersioned(shopId, customerId, request.version(), update);
        } catch (DuplicateKeyException e) {
            throw CustomerErrors.phoneAlreadyExists("phone", phone);
        }
    }

    /** Archive ({@code ARCHIVED}) or restore ({@code ACTIVE}). There is no delete. */
    public CustomerResponse changeStatus(String customerId, CustomerStatusRequest request) {
        String shopId = currentUser.shopId();
        requireValidId(customerId);
        return applyVersioned(shopId, customerId, request.version(), new Update().set("status", request.status()));
    }

    // ==== Addresses ======================================================================

    public CustomerResponse addAddress(String customerId, AddressRequest request) {
        String shopId = currentUser.shopId();
        requireValidId(customerId);
        return CustomerMapper.toResponse(changeAddresses(shopId, customerId, current -> {
            if (current.size() >= CustomerRules.MAX_ADDRESSES) {
                throw CustomerErrors.addressLimitReached();
            }
            CustomerAddress address = newAddress(request.label(), request.line(), request.area(), request.district(),
                    request.zone(), request.note());
            current.add(address);
            return withDefault(current, Boolean.TRUE.equals(request.isDefault()) ? address.getAddressId() : null);
        }));
    }

    public CustomerResponse updateAddress(String customerId, String addressId, AddressRequest request) {
        String shopId = currentUser.shopId();
        requireValidId(customerId);
        return CustomerMapper.toResponse(changeAddresses(shopId, customerId, current -> {
            CustomerAddress address = findAddress(current, addressId);
            address.setLabel(CustomerRules.normalizeText(request.label()));
            address.setLine(CustomerRules.normalizeText(request.line()));
            address.setArea(CustomerRules.normalizeText(request.area()));
            address.setDistrict(CustomerRules.normalizeText(request.district()));
            address.setZone(request.zone());
            address.setNote(CustomerRules.normalizeText(request.note()));
            return withDefault(current, Boolean.TRUE.equals(request.isDefault()) ? addressId : null);
        }));
    }

    /** If the removed address was the default, the oldest remaining one becomes default. */
    public CustomerResponse removeAddress(String customerId, String addressId) {
        String shopId = currentUser.shopId();
        requireValidId(customerId);
        return CustomerMapper.toResponse(changeAddresses(shopId, customerId, current -> {
            current.remove(findAddress(current, addressId));
            return withDefault(current, null);
        }));
    }

    // ==== Java API for the Order module ==================================================

    /**
     * Order create with an inline customer: the customer with this phone in the current shop, or a
     * new one (docs/modules/CUSTOMER.md §5). An existing customer's name is never changed; an
     * archived one becomes active again; a new delivery address is added if there is room.
     * Works inside or outside a transaction.
     */
    public OrderCustomer findOrCreateByPhone(NewOrderCustomer input) {
        String shopId = currentUser.shopId();
        String phone = normalizePhone(input.phone(), "customer.phone");
        Optional<Customer> existing = customerRepository.findByShopIdAndPhone(shopId, phone);
        if (existing.isPresent()) {
            return toOrderCustomer(attachToOrder(shopId, existing.get(), input.address()));
        }

        String name = CustomerRules.normalizeText(input.name());
        if (name == null) {
            throw new FieldValidationException("customer.name", "must not be blank");
        }
        Customer customer = new Customer();
        customer.setShopId(shopId);
        applyProfile(customer, name, phone, null, null, null, List.of());
        List<CustomerAddress> addresses = new ArrayList<>();
        if (input.address() != null) {
            AddressDto a = input.address();
            addresses.add(newAddress(null, a.line(), a.area(), a.district(), a.zone(), a.note()));
        }
        customer.setAddresses(withDefault(addresses, null));
        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setStats(CustomerStats.empty());
        customer.setCreatedBy(currentUser.userId());
        try {
            return toOrderCustomer(customerRepository.save(customer));
        } catch (DuplicateKeyException e) {
            // Another order created this phone a moment ago: use that customer.
            Customer winner = customerRepository.findByShopIdAndPhone(shopId, phone).orElseThrow(() -> e);
            return toOrderCustomer(attachToOrder(shopId, winner, input.address()));
        }
    }

    /** Order create with a {@code customerId}: 404 if unknown or of another shop. */
    public OrderCustomer getForOrder(String customerId) {
        return toOrderCustomer(load(currentUser.shopId(), customerId));
    }

    /** {@code totalOrders + 1}, {@code lastOrderAt = max(lastOrderAt, createdAt)}. Inside Order's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOrderCreated(String customerId, Instant createdAt) {
        changeStats(customerId, new Update().inc("stats.totalOrders", 1).max("stats.lastOrderAt", createdAt));
    }

    /** {@code deliveredOrders + 1}, {@code totalSpent += orderTotal} (poisha). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDelivered(String customerId, long orderTotal) {
        changeStats(customerId, new Update().inc("stats.deliveredOrders", 1).inc("stats.totalSpent", orderTotal));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReturned(String customerId) {
        changeStats(customerId, new Update().inc("stats.returnedOrders", 1));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCancelled(String customerId) {
        changeStats(customerId, new Update().inc("stats.cancelledOrders", 1));
    }

    // ==== Helpers ========================================================================

    private Customer load(String shopId, String customerId) {
        requireValidId(customerId);
        return customerRepository.findByIdAndShopId(customerId, shopId).orElseThrow(CustomerErrors::customerNotFound);
    }

    /** A malformed id cannot exist in any shop: 404, like an unknown one. */
    private static void requireValidId(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw CustomerErrors.customerNotFound();
        }
    }

    private CustomerResponse applyVersioned(String shopId, String customerId, Long expectedVersion, Update update) {
        update.set("updatedAt", Instant.now(clock)).inc("version", 1);
        Criteria versionMatches = Criteria.where("version").is(expectedVersion);
        return queryRepository.modifyVersioned(shopId, customerId, versionMatches, update)
                .map(CustomerMapper::toResponse)
                .orElseThrow(() -> customerRepository.existsByIdAndShopId(customerId, shopId)
                        ? CustomerErrors.staleVersion()
                        : CustomerErrors.customerNotFound());
    }

    /**
     * Replaces the address array with {@code change(current copy)}, as a compare-and-set on
     * {@code addressRevision}: if someone else changed the addresses in between, load again and
     * re-apply. {@code change} returns {@code null} for "nothing to change".
     */
    private Customer changeAddresses(String shopId, String customerId, UnaryOperator<List<CustomerAddress>> change) {
        for (int attempt = 1; attempt <= ADDRESS_ATTEMPTS; attempt++) {
            Customer current = load(shopId, customerId);
            List<CustomerAddress> copy = new ArrayList<>(current.getAddresses().stream().map(CustomerAddress::copy).toList());
            List<CustomerAddress> changed = change.apply(copy);
            if (changed == null) {
                return current;
            }
            Update update = new Update()
                    .set("addresses", queryRepository.addressDocuments(changed))
                    .inc("addressRevision", 1)
                    .set("updatedAt", Instant.now(clock));
            Criteria sameRevision = Criteria.where("addressRevision").is(current.getAddressRevision());
            Optional<Customer> saved = queryRepository.modifyUnversioned(shopId, customerId, sameRevision, update);
            if (saved.isPresent()) {
                return saved.get();
            }
        }
        throw CustomerErrors.staleVersion();
    }

    /** Existing customer for an order: reactivate if archived, add the delivery address if new. */
    private Customer attachToOrder(String shopId, Customer customer, AddressDto address) {
        Customer current = customer;
        if (current.getStatus() == CustomerStatus.ARCHIVED) {
            current = queryRepository.modifyUnversioned(shopId, current.getId(), null,
                            new Update().set("status", CustomerStatus.ACTIVE).set("updatedAt", Instant.now(clock)))
                    .orElse(current);
        }
        if (address == null) {
            return current;
        }
        String signature = CustomerRules.addressSignature(address.line(), address.area(), address.district(), address.zone());
        return changeAddresses(shopId, current.getId(), list -> {
            boolean known = list.stream().anyMatch(a ->
                    CustomerRules.addressSignature(a.getLine(), a.getArea(), a.getDistrict(), a.getZone()).equals(signature));
            if (known || list.size() >= CustomerRules.MAX_ADDRESSES) {
                return null; // nothing to change; the order keeps its own address snapshot anyway
            }
            list.add(newAddress(null, address.line(), address.area(), address.district(), address.zone(), address.note()));
            return withDefault(list, null);
        });
    }

    private void changeStats(String customerId, Update update) {
        String shopId = currentUser.shopId();
        requireValidId(customerId);
        if (queryRepository.modifyUnversioned(shopId, customerId, null, update).isEmpty()) {
            throw CustomerErrors.customerNotFound();
        }
    }

    private static OrderCustomer toOrderCustomer(Customer c) {
        return new OrderCustomer(c.getId(), c.getName(), c.getPhone(), c.getStatus(),
                CustomerMapper.defaultAddress(c).map(CustomerMapper::toAddress).orElse(null));
    }

    private static void applyProfile(Customer target, String name, String phone, String altPhone,
                                     String facebookProfileUrl, String note, List<String> tags) {
        String normalizedName = CustomerRules.normalizeText(name);
        List<String> normalizedTags = CustomerRules.normalizeTags(tags);
        target.setName(normalizedName);
        target.setNameLower(CustomerRules.lower(normalizedName));
        target.setPhone(phone);
        target.setAltPhone(altPhone);
        target.setPhoneKeys(new ArrayList<>(CustomerRules.phoneKeys(phone, altPhone)));
        target.setPhoneKeysReversed(new ArrayList<>(CustomerRules.phoneKeysReversed(phone, altPhone)));
        target.setFacebookProfileUrl(blankToNull(facebookProfileUrl));
        target.setNote(note == null || note.isBlank() ? null : note.trim());
        target.setTags(normalizedTags);
        target.setTagKeys(new ArrayList<>(CustomerRules.tagKeys(normalizedTags)));
    }

    private static String normalizePhone(String phone, String field) {
        return BdPhone.tryNormalize(phone).orElseThrow(() -> new FieldValidationException(field, BdPhone.INVALID_MESSAGE));
    }

    private static String normalizeAltPhone(String altPhone, String phone) {
        if (altPhone == null || altPhone.isBlank()) {
            return null;
        }
        String normalized = normalizePhone(altPhone, "altPhone");
        if (normalized.equals(phone)) {
            throw new FieldValidationException("altPhone", "must be different from phone");
        }
        return normalized;
    }

    private static CustomerAddress newAddress(String label, String line, String area, String district,
                                              com.sellerdesk.common.model.DeliveryZone zone, String note) {
        CustomerAddress a = new CustomerAddress();
        a.setAddressId(new ObjectId().toHexString());
        a.setLabel(CustomerRules.normalizeText(label));
        a.setLine(CustomerRules.normalizeText(line));
        a.setArea(CustomerRules.normalizeText(area));
        a.setDistrict(CustomerRules.normalizeText(district));
        a.setZone(zone);
        a.setNote(CustomerRules.normalizeText(note));
        return a;
    }

    /**
     * Exactly one default when the list is not empty: {@code preferredId} if given, else the current
     * default, else the oldest (first) address.
     */
    static List<CustomerAddress> withDefault(List<CustomerAddress> addresses, String preferredId) {
        String chosen = preferredId;
        if (chosen == null) {
            chosen = addresses.stream().filter(CustomerAddress::isDefault).map(CustomerAddress::getAddressId)
                    .findFirst().orElse(addresses.isEmpty() ? null : addresses.get(0).getAddressId());
        }
        for (CustomerAddress a : addresses) {
            a.setDefault(a.getAddressId().equals(chosen));
        }
        return addresses;
    }

    private static CustomerAddress findAddress(List<CustomerAddress> addresses, String addressId) {
        return addresses.stream().filter(a -> a.getAddressId().equals(addressId)).findFirst()
                .orElseThrow(CustomerErrors::addressNotFound);
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CustomerStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank() || "ACTIVE".equals(status)) {
            return CustomerStatus.ACTIVE;
        }
        if ("ALL".equals(status)) {
            return null;
        }
        if ("ARCHIVED".equals(status)) {
            return CustomerStatus.ARCHIVED;
        }
        throw new FieldValidationException("status", "must be ACTIVE, ARCHIVED or ALL");
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank() || "createdAt,desc".equals(sort)) {
            return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id"));
        }
        if ("name,asc".equals(sort)) {
            return Sort.by(Sort.Order.asc("nameLower"), Sort.Order.asc("_id"));
        }
        if ("lastOrderAt,desc".equals(sort)) {
            return Sort.by(Sort.Order.desc("stats.lastOrderAt"), Sort.Order.desc("_id"));
        }
        throw new FieldValidationException("sort", "must be createdAt,desc, name,asc or lastOrderAt,desc");
    }
}
