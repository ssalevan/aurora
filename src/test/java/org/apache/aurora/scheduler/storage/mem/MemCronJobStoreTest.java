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
package org.apache.aurora.scheduler.storage.mem;

import com.google.common.base.Optional;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.util.Modules;

import org.apache.aurora.common.stats.SlidingStats;
import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.common.util.Clock;
import org.apache.aurora.scheduler.storage.AbstractCronJobStoreTest;
import org.apache.aurora.scheduler.storage.db.DbModule;
import org.apache.aurora.scheduler.storage.db.InstrumentingInterceptor;
import org.apache.aurora.scheduler.testing.FakeStatsProvider;
import org.junit.Test;

import static org.apache.aurora.common.inject.Bindings.KeyFactory.PLAIN;
import static org.apache.aurora.scheduler.storage.mem.MemCronJobStore.CRON_JOBS_SIZE;
import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;

public class MemCronJobStoreTest extends AbstractCronJobStoreTest {

  private FakeStatsProvider statsProvider;

  @Override
  protected Module getStorageModule() {
    statsProvider = new FakeStatsProvider();
    return Modules.combine(
        DbModule.testModuleWithWorkQueue(PLAIN, Optional.of(new InMemStoresModule(PLAIN))),
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(StatsProvider.class).toInstance(statsProvider);

            // bindings for mybatis interceptor
            SlidingStats slidingStats = createMock(SlidingStats.class);
            bind(InstrumentingInterceptor.class).toInstance(new InstrumentingInterceptor(
                Clock.SYSTEM_CLOCK, s -> slidingStats
            ));
          }
        });
  }

  @Test
  public void testStoreSize() {
    assertEquals(0L, statsProvider.getLongValue(CRON_JOBS_SIZE));
    saveAcceptedJob(JOB_A);
    assertEquals(1L, statsProvider.getLongValue(CRON_JOBS_SIZE));
    saveAcceptedJob(JOB_B);
    assertEquals(2L, statsProvider.getLongValue(CRON_JOBS_SIZE));
    deleteJobs();
    assertEquals(0L, statsProvider.getLongValue(CRON_JOBS_SIZE));
  }
}
