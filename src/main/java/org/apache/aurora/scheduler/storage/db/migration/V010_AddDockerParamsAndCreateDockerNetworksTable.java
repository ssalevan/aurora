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
package org.apache.aurora.scheduler.storage.db.migration;

import java.math.BigDecimal;

import org.apache.ibatis.migration.MigrationScript;

public class V010_AddDockerParamsAndCreateDockerNetworksTable implements MigrationScript {
  @Override
  public BigDecimal getId() {
    return BigDecimal.valueOf(10L);
  }

  @Override
  public String getDescription() {
    return "Creates the docker_networks table and modifies task_config_docker_containers";
  }

  @Override
  public String getUpScript() {
    return "CREATE TABLE IF NOT EXISTS docker_networks("
        + "id INT PRIMARY KEY,"
        + "name VARCHAR NOT NULL,"
        + "UNIQUE(name)"
        + ");"
        + "ALTER TABLE task_config_docker_containers ADD COLUMN IF NOT EXISTS force_pull_image BOOLEAN NOT NULL;"
        + "ALTER TABLE task_config_docker_containers ADD COLUMN IF NOT EXISTS network INT NOT NULL;"
        + "ALTER TABLE task_config_docker_containers ADD COLUMN IF NOT EXISTS user_network VARCHAR NOT NULL;"
        + "ALTER TABLE task_config_docker_containers ADD COLUMN IF NOT EXISTS command VARCHAR NOT NULL;"
        + "ALTER TABLE task_config_docker_containers ADD CONSTRAINT DOCKER_NETWORK FOREIGN KEY(network) REFERENCES docker_networks(id);";
  }

  @Override
  public String getDownScript() {
    return "ALTER TABLE task_config_docker_containers DROP CONSTRAINT DOCKER_NETWORK;"
        + "ALTER TABLE task_config_docker_containers DROP COLUMN IF EXISTS force_pull_image;"
        + "ALTER TABLE task_config_docker_containers DROP COLUMN IF EXISTS network;"
        + "ALTER TABLE task_config_docker_containers DROP COLUMN IF EXISTS user_network;"
        + "ALTER TABLE task_config_docker_containers DROP COLUMN IF EXISTS command;"
        + "DROP TABLE IF EXISTS docker_networks;";
  }
}
