redis.call('incr', KEYS[1])
return redis.call('get', KEYS[1])