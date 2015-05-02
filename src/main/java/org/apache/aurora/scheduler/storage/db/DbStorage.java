/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.storage.db;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import com.twitter.common.inject.TimedInterceptor.Timed;

import org.apache.aurora.gen.JobUpdateAction;
import org.apache.aurora.gen.JobUpdateStatus;
import org.apache.aurora.gen.MaintenanceMode;
import org.apache.aurora.scheduler.storage.AttributeStore;
import org.apache.aurora.scheduler.storage.CronJobStore;
import org.apache.aurora.scheduler.storage.JobUpdateStore;
import org.apache.aurora.scheduler.storage.LockStore;
import org.apache.aurora.scheduler.storage.QuotaStore;
import org.apache.aurora.scheduler.storage.SchedulerStore;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.TaskStore;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.MappedStatement.Builder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.guice.transactional.Transactional;

import static java.util.Objects.requireNonNull;

import static org.apache.ibatis.mapping.SqlCommandType.UPDATE;

/**
 * A storage implementation backed by a relational database.
 * <p>
 * Delegates read and write concurrency semantics to the underlying database.
 */
class DbStorage extends AbstractIdleService implements Storage {

  private final SqlSessionFactory sessionFactory;
  private final MutableStoreProvider storeProvider;
  private final EnumValueMapper enumValueMapper;

  @Inject
  DbStorage(
      SqlSessionFactory sessionFactory,
      EnumValueMapper enumValueMapper,
      final CronJobStore.Mutable cronJobStore,
      final TaskStore.Mutable taskStore,
      final SchedulerStore.Mutable schedulerStore,
      final AttributeStore.Mutable attributeStore,
      final LockStore.Mutable lockStore,
      final QuotaStore.Mutable quotaStore,
      final JobUpdateStore.Mutable jobUpdateStore) {

    this.sessionFactory = requireNonNull(sessionFactory);
    this.enumValueMapper = requireNonNull(enumValueMapper);
    requireNonNull(cronJobStore);
    requireNonNull(taskStore);
    requireNonNull(schedulerStore);
    requireNonNull(attributeStore);
    requireNonNull(lockStore);
    requireNonNull(quotaStore);
    requireNonNull(jobUpdateStore);
    storeProvider = new MutableStoreProvider() {
      @Override
      public SchedulerStore.Mutable getSchedulerStore() {
        return schedulerStore;
      }

      @Override
      public CronJobStore.Mutable getCronJobStore() {
        return cronJobStore;
      }

      @Override
      public TaskStore getTaskStore() {
        return taskStore;
      }

      @Override
      public TaskStore.Mutable getUnsafeTaskStore() {
        return taskStore;
      }

      @Override
      public LockStore.Mutable getLockStore() {
        return lockStore;
      }

      @Override
      public QuotaStore.Mutable getQuotaStore() {
        return quotaStore;
      }

      @Override
      public AttributeStore.Mutable getAttributeStore() {
        return attributeStore;
      }

      @Override
      public JobUpdateStore.Mutable getJobUpdateStore() {
        return jobUpdateStore;
      }
    };
  }

  @Timed("db_storage_read_operation")
  @Override
  @Transactional
  public <T, E extends Exception> T read(Work<T, E> work) throws StorageException, E {
    try {
      return work.apply(storeProvider);
    } catch (PersistenceException e) {
      throw new StorageException(e.getMessage(), e);
    }
  }

  @Timed("db_storage_write_operation")
  @Override
  @Transactional
  public <T, E extends Exception> T write(MutateWork<T, E> work) throws StorageException, E {
    try {
      return work.apply(storeProvider);
    } catch (PersistenceException e) {
      throw new StorageException(e.getMessage(), e);
    }
  }

  @VisibleForTesting
  static final String DISABLE_UNDO_LOG = "DISABLE_UNDO_LOG";
  @VisibleForTesting
  static final String ENABLE_UNDO_LOG = "ENABLE_UNDO_LOG";

  // TODO(wfarner): Including @Transactional here seems to render the UNDO_LOG changes useless,
  // resulting in no performance gain.  Figure out why.
  @Timed("db_storage_bulk_load_operation")
  @Override
  public <E extends Exception> void bulkLoad(MutateWork.NoResult<E> work)
      throws StorageException, E {

    // Disabling the undo log disables transaction rollback, but dramatically speeds up a bulk
    // insert.
    try (SqlSession session = sessionFactory.openSession(false)) {
      try {
        session.update(DISABLE_UNDO_LOG);
        work.apply(storeProvider);
      } catch (PersistenceException e) {
        throw new StorageException(e.getMessage(), e);
      } finally {
        session.update(ENABLE_UNDO_LOG);
      }
    }
  }

  @Override
  public void prepare() {
    startAsync().awaitRunning();
  }

  private static void addMappedStatement(Configuration configuration, String name, String sql) {
    configuration.addMappedStatement(
        new Builder(configuration, name, new StaticSqlSource(configuration, sql), UPDATE).build());
  }

  /**
   * Creates the SQL schema during service start-up.
   * Note: This design assumes a volatile database engine.
   */
  @Override
  @Transactional
  protected void startUp() throws IOException {
    Configuration configuration = sessionFactory.getConfiguration();
    String createStatementName = "create_tables";
    configuration.setMapUnderscoreToCamelCase(true);

    addMappedStatement(
        configuration,
        createStatementName,
        CharStreams.toString(new InputStreamReader(
            DbStorage.class.getResourceAsStream("schema.sql"),
            StandardCharsets.UTF_8)));
    addMappedStatement(configuration, DISABLE_UNDO_LOG, "SET UNDO_LOG 0;");
    addMappedStatement(configuration, ENABLE_UNDO_LOG, "SET UNDO_LOG 1;");

    try (SqlSession session = sessionFactory.openSession()) {
      session.update(createStatementName);
    }

    for (MaintenanceMode mode : MaintenanceMode.values()) {
      enumValueMapper.addEnumValue("maintenance_modes", mode.getValue(), mode.name());
    }

    for (JobUpdateStatus status : JobUpdateStatus.values()) {
      enumValueMapper.addEnumValue("job_update_statuses", status.getValue(), status.name());
    }

    for (JobUpdateAction action : JobUpdateAction.values()) {
      enumValueMapper.addEnumValue("job_instance_update_actions", action.getValue(), action.name());
    }
  }

  @Override
  protected void shutDown() {
    // noop
  }
}
