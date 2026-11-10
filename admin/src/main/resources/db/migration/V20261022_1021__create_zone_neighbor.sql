-- 구역의 방향별 이웃: tables.md 'zone_neighbor'. 관리자가 구역마다 동서남북 이웃을 지정하고, 반대쪽 방향은 같이 맞춘다(FNC-A-016)

CREATE TABLE zone_neighbor (
    zone_id BIGINT NOT NULL COMMENT '기준 구역(zone.id)',
    direction CHAR(1) NOT NULL COMMENT '방향: N / E / S / W',
    neighbor_id BIGINT NOT NULL COMMENT '그 방향의 이웃 구역(zone.id). 행이 없는 방향은 관측 범위 밖',
    CONSTRAINT zone_neighbor_pk PRIMARY KEY (zone_id, direction),
    CONSTRAINT zone_neighbor_fk_zone FOREIGN KEY (zone_id) REFERENCES zone (id),
    CONSTRAINT zone_neighbor_fk_neighbor FOREIGN KEY (neighbor_id) REFERENCES zone (id)
) ENGINE = InnoDB;

CREATE INDEX zone_neighbor_ix_neighbor ON zone_neighbor (neighbor_id);
