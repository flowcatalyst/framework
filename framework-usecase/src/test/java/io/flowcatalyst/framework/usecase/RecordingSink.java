package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/** A sink that remembers what it was given, and can be told to fail. */
final class RecordingSink implements Sink {

  final List<DomainEvent> events = new CopyOnWriteArrayList<>();
  final List<AuditEntry> audits = new CopyOnWriteArrayList<>();
  volatile @Nullable Exception failEvents;
  volatile @Nullable Exception failAudit;

  @Override
  public void writeEvents(Tx tx, Context context, List<DomainEvent> written) throws Exception {
    Exception failure = failEvents;
    if (failure != null) {
      throw failure;
    }
    events.addAll(new ArrayList<>(written));
  }

  @Override
  public void writeAudit(Tx tx, AuditEntry entry) throws Exception {
    Exception failure = failAudit;
    if (failure != null) {
      throw failure;
    }
    audits.add(entry);
  }
}
