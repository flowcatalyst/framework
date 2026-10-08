package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Persistence of one kind of aggregate, written by the application with explicit SQL
 * (write-path contract, section 2).
 *
 * <p>Writes carry the version check: {@code update} must set {@code version = a.version() + 1}
 * and match {@code WHERE id = ? AND client_id = ? AND version = a.version()}, and {@code delete}
 * must match the same way. The runner reads the returned row count: {@code 0} means someone
 * else committed a change since the aggregate was loaded.
 *
 * @param <I> the id type
 * @param <A> the aggregate type
 */
public interface Repository<I extends AggregateId, A extends Aggregate> {

  /**
   * Loads an aggregate, without a transaction. Must filter by {@code client_id}.
   *
   * @param db the database to read from
   * @param client the client
   * @param id the id
   * @return the aggregate, or empty when there is none for that client
   * @throws SQLException on a database error
   */
  Optional<A> find(Database db, ClientId client, I id) throws SQLException;

  /**
   * Stores a new aggregate, at version {@code 1}, with its children.
   *
   * @param tx the attempt's transaction
   * @param aggregate the aggregate, whose {@code version()} is {@code 0}
   * @throws SQLException on a database error
   */
  void insert(Tx tx, A aggregate) throws SQLException;

  /**
   * Updates the root row (version-checked) and then writes the children.
   *
   * @param tx the attempt's transaction
   * @param aggregate the changed aggregate, at the version it was loaded with
   * @return the root update's row count
   * @throws SQLException on a database error
   */
  int update(Tx tx, A aggregate) throws SQLException;

  /**
   * Deletes the root row (version-checked); children by cascade or explicitly.
   *
   * @param tx the attempt's transaction
   * @param aggregate the aggregate, at the version it was loaded with
   * @return the root delete's row count
   * @throws SQLException on a database error
   */
  int delete(Tx tx, A aggregate) throws SQLException;
}
