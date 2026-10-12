package com.example.orderms.catalog.infrastructure.persistence;

import com.example.orderms.catalog.application.port.out.ProductStore;
import com.example.orderms.catalog.domain.Product;
import com.example.orderms.shared.domain.Money;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class JpaProductStore implements ProductStore {
  private final EntityManager em;

  public JpaProductStore(EntityManager em) {
    this.em = em;
  }

  public List<Product> active() {
    return em
        .createQuery(
            "select p from ProductEntity p where p.deletedAt is null order by p.id",
            ProductEntity.class)
        .getResultList()
        .stream()
        .map(this::map)
        .toList();
  }

  public Optional<Product> find(UUID id) {
    return Optional.ofNullable(em.find(ProductEntity.class, id)).map(this::map);
  }

  public Optional<Product> findForPurchase(UUID id) {
    return Optional.ofNullable(
            em.find(ProductEntity.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_READ))
        .map(this::map);
  }

  public void save(Product p) {
    var e = em.find(ProductEntity.class, p.id());
    if (e == null) throw new IllegalStateException("Product must exist");
    if (e.version != p.version()) throw new jakarta.persistence.OptimisticLockException();
    e.deletedAt = p.deletedAt();
    e.updatedAt = p.updatedAt();
    em.flush();
  }

  private Product map(ProductEntity e) {
    return new Product(
        e.id,
        e.name,
        e.description,
        new Money(e.price, e.currency),
        e.available,
        e.createdAt,
        e.updatedAt,
        e.deletedAt,
        e.version);
  }
}
