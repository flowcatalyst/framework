-- name: GetThing :one
SELECT id, name, note, created_at FROM things WHERE id = $1;

-- name: ListThings :many
SELECT id, name, note FROM things ORDER BY id;

-- name: InsertThing :exec
INSERT INTO things (id, name, note) VALUES ($1, $2, $3);

-- name: ThingsWithTags :many
SELECT t.id, t.name, g.tag, g.weight, g.thing_id
  FROM things t LEFT JOIN thing_tags g ON g.thing_id = t.id
 ORDER BY t.id;
