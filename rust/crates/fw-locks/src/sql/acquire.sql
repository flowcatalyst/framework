INSERT INTO fw_lock (lock_key, owner_token, fencing_token, expires_at)
VALUES (?, ?, nextval('fw_lock_fence'), clock_timestamp() + ? * interval '1 millisecond')
ON CONFLICT (lock_key) DO UPDATE
   SET owner_token = EXCLUDED.owner_token,
       fencing_token = EXCLUDED.fencing_token,
       expires_at = EXCLUDED.expires_at
 WHERE fw_lock.expires_at <= clock_timestamp()
RETURNING fencing_token