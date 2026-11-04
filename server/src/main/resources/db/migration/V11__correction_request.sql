-- 更正申请：学生发现录错了，提交"改成什么、为什么"，老师审核通过后才改数据

CREATE TABLE correction_request (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    observation_id BIGINT        NOT NULL,
    trial_id       BIGINT        NOT NULL,
    kind           VARCHAR(10)   NOT NULL,          -- UPDATE 改值 / DELETE 删掉（多录的、录错小区的）
    base_version   INT           NOT NULL,          -- 申请时数据的版本；之后又被改过就不能直接通过
    old_display    VARCHAR(600)  NOT NULL,          -- 申请时的值（显示用的文字）
    old_observed_on DATE         NOT NULL,          -- 申请时的调查日期
    observed_on    DATE,                            -- 要改成的调查日期，不改时为空
    value_num      DECIMAL(14, 4),
    value_text     VARCHAR(500),
    value_date     DATE,
    note           VARCHAR(500),
    new_display    VARCHAR(600),                    -- 要改成的值（显示用的文字）
    reason         VARCHAR(500)  NOT NULL,
    status         VARCHAR(10)   NOT NULL,          -- PENDING 待审核 / APPROVED 通过 / REJECTED 驳回 / WITHDRAWN 撤回
    requested_by   BIGINT        NOT NULL,
    requested_at   DATETIME      NOT NULL,
    reviewed_by    BIGINT,
    reviewed_at    DATETIME,
    review_note    VARCHAR(500),
    CONSTRAINT fk_corr_obs FOREIGN KEY (observation_id) REFERENCES observation (id) ON DELETE CASCADE,
    CONSTRAINT fk_corr_trial FOREIGN KEY (trial_id) REFERENCES trial (id) ON DELETE CASCADE,
    CONSTRAINT fk_corr_requester FOREIGN KEY (requested_by) REFERENCES app_user (id),
    CONSTRAINT fk_corr_reviewer FOREIGN KEY (reviewed_by) REFERENCES app_user (id)
);
CREATE INDEX idx_corr_status ON correction_request (status, requested_at);
CREATE INDEX idx_corr_obs ON correction_request (observation_id, status);
CREATE INDEX idx_corr_requester ON correction_request (requested_by, requested_at);

-- 修改记录的补充说明，例如"按李同学的更正申请"
ALTER TABLE audit_log ADD COLUMN note VARCHAR(300);

INSERT INTO app_module (code, name, description, kind, required, enabled, sort_order) VALUES
    ('corrections', '更正申请', '学生发现录错了提交更正，老师审核通过后才改数据，修改记录里记下是谁申请的', 'BUILTIN', FALSE, TRUE, 86);
