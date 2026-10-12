UPDATE fw_lock SET owner_token = NULL, expires_at = clock_timestamp()
 WHERE lock_key = ? AND owner_token = ? AND fencing_token = ? AND expires_at > clock_timestamp()