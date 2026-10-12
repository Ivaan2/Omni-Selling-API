package com.example.orderms.shared.infrastructure;

import com.example.orderms.shared.application.TransactionBoundary;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringTransactionBoundary implements TransactionBoundary {
  private final TransactionTemplate template;
  private final TransactionTemplate reads;

  public SpringTransactionBoundary(PlatformTransactionManager manager) {
    template = new TransactionTemplate(manager);
    template.setTimeout(10);
    reads = new TransactionTemplate(manager);
    reads.setReadOnly(true);
    reads.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    reads.setTimeout(10);
  }

  public <T> T read(java.util.function.Supplier<T> work) {
    return reads.execute(status -> work.get());
  }

  public <T> T execute(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
