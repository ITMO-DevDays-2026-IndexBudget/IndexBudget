\set ON_ERROR_STOP on
CREATE TABLE demo_meta.experiment_guard (disposable boolean NOT NULL CHECK (disposable));
INSERT INTO demo_meta.experiment_guard VALUES (true);
REVOKE ALL ON demo_meta.experiment_guard FROM PUBLIC;
