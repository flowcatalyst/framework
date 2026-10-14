UPDATE fw_lock SET expires_at = clock_timestamp() + ? * interval '1 millisecond'
 WHERE lock_key = ? AND owner_token = ? AND fencing_token = ? AND expires_at > clock_timestamp()