package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.usecase.Repository;
import io.flowcatalyst.framework.usecase.Tx;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** The client's SLA policy; the SQL is in {@code SlaSql.sql}. */
public class SlaPolicyRepository implements Repository<SlaPolicyId, SlaPolicy> {

  @Override
  public Optional<SlaPolicy> find(Database db, ClientId client, SlaPolicyId id) throws SQLException {
    try (Connection c = db.dataSource().getConnection()) {
      return SlaSql.findPolicy(c, id, client)
          .map(r -> new SlaPolicy(id, client, r.version(), r.lowMinutes(), r.normalMinutes(), r.highMinutes(), r.urgentMinutes()));
    }
  }

  /**
   * The client's policy, whatever its id: there is at most one.
   *
   * @param db the database
   * @param client the client
   * @return the policy, or empty when the client has not set one
   * @throws SQLException on a database error
   */
  public Optional<SlaPolicy> findByClient(Database db, ClientId client) throws SQLException {
    try (Connection c = db.dataSource().getConnection()) {
      return SlaSql.findByClient(c, client)
          .map(
              r ->
                  new SlaPolicy(
                      r.id(), client, r.version(), r.lowMinutes(), r.normalMinutes(), r.highMinutes(), r.urgentMinutes()));
    }
  }

  @Override
  public void insert(Tx tx, SlaPolicy p) throws SQLException {
    SlaSql.insertPolicy(tx.connection(), p.id(), p.client(), p.lowMinutes(), p.normalMinutes(), p.highMinutes(), p.urgentMinutes());
  }

  @Override
  public int update(Tx tx, SlaPolicy p) throws SQLException {
    return SlaSql.updatePolicy(
        tx.connection(), p.version() + 1, p.lowMinutes(), p.normalMinutes(), p.highMinutes(), p.urgentMinutes(), p.id(),
        p.client(), p.version());
  }

  @Override
  public int delete(Tx tx, SlaPolicy p) throws SQLException {
    return SlaSql.deletePolicy(tx.connection(), p.id(), p.client(), p.version());
  }
}
