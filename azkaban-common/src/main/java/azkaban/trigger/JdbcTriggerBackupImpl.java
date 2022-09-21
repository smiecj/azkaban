/*
 * Copyright 2017 LinkedIn Corp.
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

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.apache.log4j.Logger;

import com.google.inject.Inject;

import azkaban.db.DatabaseOperator;

@Singleton
public class JdbcTriggerBackupImpl implements TriggerBackupLoader {

  private static final String TRIGGER_BACKUP_TABLE_NAME = "triggers_backup";
  private static final Duration BACKUP_EXPIRE_DURATION = Duration.ofDays(180);
  
  private static final String ADD_TRIGGER_BACKUP =
      "INSERT INTO " + TRIGGER_BACKUP_TABLE_NAME + " (project_id, backup_date, project_name, flow_id, cron) values (?, ?, ?, ?, ?)";
  private static final String REMOVE_EXPIRE_TRIGGER_BACKUP =
      "DELETE FROM " + TRIGGER_BACKUP_TABLE_NAME + " WHERE backup_date < ?";

  private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private static final Logger logger = Logger.getLogger(JdbcTriggerBackupImpl.class);
  private final DatabaseOperator dbOperator;

  @Inject
  public JdbcTriggerBackupImpl(final DatabaseOperator databaseOperator) {
    this.dbOperator = databaseOperator;
  }

  @Override
  public int addTriggerBackupList(List<TriggerBackup> tList) throws TriggerLoaderException {
    final int batchAddSize = 10;
    int start = 0, total = 0;
    logger.info("addTriggerBackupList size: " + tList.size());
    try {
        while (start < tList.size()) {
        // batch insert
          int endIndex = Math.min(start + batchAddSize, tList.size());
          List<TriggerBackup> backupSubList = tList.subList(start, endIndex);
          Object[][] parameters = backupSubList.stream()
          .map(item -> {
            ArrayList<Object> object = new ArrayList<>();
            object.add(item.getProjectId());
            object.add(item.getBackupDate());
            object.add(item.getProjectName());
            object.add(item.getFlowId());
            object.add(item.getCron());
            return object.toArray();
        })
        .collect(Collectors.toList()).toArray(new Object[0][]);
        int[] addCountArr = this.dbOperator.batch(ADD_TRIGGER_BACKUP, parameters);
        
        total += Arrays.stream(addCountArr).reduce(0, Integer::sum);
        start += batchAddSize;
      }
    } catch (final SQLException ex) {
      throw new TriggerLoaderException("save trigger backup failed",
          ex);
    }
    logger.info("addTriggerBackupList finish");
    return total;
  }

  @Override
  public int removeBeforeDate(String date) throws TriggerLoaderException {
    // get to remove date
    final LocalDateTime toRemoveTime = LocalDateTime.now().minus(BACKUP_EXPIRE_DURATION);
    String toRemoveDate = dateFormatter.format(toRemoveTime);
    logger.info("removeBeforeDate date: " + toRemoveDate);
    try {
      return this.dbOperator.update(REMOVE_EXPIRE_TRIGGER_BACKUP, toRemoveDate);
    } catch (final SQLException ex) {
      throw new TriggerLoaderException("remove expire trigger backup failed",
          ex);
    }
  }
}
