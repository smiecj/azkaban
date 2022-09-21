/*
 * Copyright 2012 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.trigger;

import static java.util.Objects.requireNonNull;

public class TriggerBackup {

  private int id;
  private int projectId;
  private String projectName;
  private String flowId;
  private String backupDate;
  private String cron;

  public TriggerBackup(final int projectId, final String projectName, 
    final String flowId, final String backupDate, final String cron) {
    requireNonNull(backupDate);
    requireNonNull(projectName);
    requireNonNull(flowId);

    this.projectId = projectId;
    this.projectName = projectName;
    this.flowId = flowId;
    this.backupDate = backupDate;
    this.cron = cron;
  }

  public int getProjectId() {
      return projectId;
  }

  public String getBackupDate() {
      return backupDate;
  }

  public String getProjectName() {
      return projectName;
  }

  public String getFlowId() {
      return flowId;
  }

  public String getCron() {
    return cron;
  }
}
