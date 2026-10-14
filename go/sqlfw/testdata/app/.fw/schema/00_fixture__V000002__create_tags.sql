CREATE TABLE thing_tags (
    thing_id bigint NOT NULL REFERENCES things (id),
    tag      text   NOT NULL,
    weight   int    NOT NULL,
    PRIMARY KEY (thing_id, tag)
);
