CREATE TABLE project_flows (
  project_id    INT    NOT NULL,
  version       INT    NOT NULL,
  flow_id       VARCHAR(256),
  modified_time BIGINT NOT NULL,
  encoding_type TINYINT,
  json          MEDIUMBLOB,
  PRIMARY KEY (project_id, version, flow_id)
);

CREATE INDEX flow_index
  ON project_flows (project_id, version);
