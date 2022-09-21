CREATE TABLE triggers (
  trigger_id     INT    NOT NULL AUTO_INCREMENT,
  trigger_source VARCHAR(128),
  modify_time    BIGINT NOT NULL,
  enc_type       TINYINT,
  data           LONGBLOB,
  PRIMARY KEY (trigger_id)
);

CREATE TABLE triggers_backup (
  id           INT    NOT NULL AUTO_INCREMENT,
  project_id   INT,
  backup_date  VARCHAR(16),
  project_name VARCHAR(64),
  flow_id      VARCHAR(256),
  cron         VARCHAR(16),
  PRIMARY KEY (id)
);

CREATE INDEX trigger_date_index
  ON triggers_backup (backup_date, project_name);