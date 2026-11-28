package com.sellerdesk.customer.repository;

import com.sellerdesk.customer.model.Customer;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Simple customer queries. Every method takes {@code shopId} (docs/SECURITY.md §4, layer 3).
 *
 * <p>Extends {@link Repository}, not {@code MongoRepository}: no inherited {@code findById} /
 * {@code findAll} / {@code deleteById} that would ignore the tenant, and no delete at all
 * (customers are archived). {@link #save} is <b>only for creating</b>; later changes are targeted
 * updates in {@link CustomerQueryRepository}.
 */
public interface CustomerRepository extends Repository<Customer, String> {

    Optional<Customer> findByIdAndShopId(String id, String shopId);

    Optional<Customer> findByShopIdAndPhone(String shopId, String phone);

    boolean existsByIdAndShopId(String id, String shopId);

    /** Create only. */
    Customer save(Customer customer);
}
