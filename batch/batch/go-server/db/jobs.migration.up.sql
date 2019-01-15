CREATE DATABASE IF NOT EXISTS jobs;

CREATE TABLE IF NOT EXISTS jobs.job (
    id INT AUTO_INCREMENT PRIMARY KEY,
     INT,
    attributes JSON,
    callback JSON,
    pod_template JSON,
    pod_status JSON,
    exit_code SMALLINT
    start_time TIMESTAMP
    end_time TIMESTAMP
) ENGINE=INNODB;

CREATE TABLE IF NOT EXISTS jobs.batch (
    id INT AUTO_INCREMENT PRIMARY KEY,
    parent_id INT,
    attributes JSON
) ENGINE=INNODB;

CREATE TABLE IF NOT EXISTS jobs.job_batch (
    id INT AUTO_INCREMENT PRIMARY KEY,
    job_id  INT,
    batch_id INT,
    FOREIGN KEY (job_id) REFERENCES job(id),
    FOREIGN KEY (batch_id) REFERENCES batch(id) 
) ENGINE=INNODB;