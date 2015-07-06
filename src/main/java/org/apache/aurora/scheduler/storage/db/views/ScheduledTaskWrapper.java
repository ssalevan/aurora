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
package org.apache.aurora.scheduler.storage.db.views;

import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.scheduler.storage.db.InsertResult;

/**
 * Representation of a row in the tasks table.
 */
public class ScheduledTaskWrapper extends InsertResult {
  private final long taskConfigRowId;
  private final ScheduledTask task;

  private ScheduledTaskWrapper() {
    // Needed by mybatis.
    this(-1, null);
  }

  public ScheduledTaskWrapper(long taskConfigRowId, ScheduledTask task) {
    this.taskConfigRowId = taskConfigRowId;
    this.task = task;
  }

  public long getTaskConfigRowId() {
    return taskConfigRowId;
  }

  public ScheduledTask getTask() {
    return task;
  }
}
