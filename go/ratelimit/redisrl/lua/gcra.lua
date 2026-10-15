local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
local interval = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local window = burst * interval
local stored = redis.call('GET', KEYS[1])
local tat = now
if stored then
  stored = tonumber(stored)
  tat = math.min(math.max(stored, now), now + window)
end
local nxt = tat + interval * cost
local allow_at = nxt - window
if now < allow_at then
  if stored and tat < stored then
    redis.call('SET', KEYS[1], string.format('%d', tat), 'PX', math.ceil((tat - now) / 1000) + 1)
  end
  return {0, allow_at - now, tat - now}
end
redis.call('SET', KEYS[1], string.format('%d', nxt), 'PX', math.ceil((nxt - now) / 1000) + 1)
return {1, math.floor((now - allow_at) / interval), nxt - now}