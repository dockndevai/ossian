-- Token bucket, evaluated atomically inside Redis.
--
-- A fixed-window counter is simpler and wrong at the edges: a caller can spend a full window's
-- allowance in the last instant of one window and again in the first instant of the next, so a
-- limit of 60/minute permits 120 in two seconds. A bucket refills continuously and has no edge.
--
-- KEYS[1] bucket    ARGV[1] capacity   ARGV[2] refill per second
-- ARGV[3] now (ms)  ARGV[4] cost
-- Returns { allowed, remaining, retry_after_ms }

local bucket    = KEYS[1]
local capacity  = tonumber(ARGV[1])
local refill    = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local cost      = tonumber(ARGV[4])

local state   = redis.call('HMGET', bucket, 'tokens', 'updated')
local tokens  = tonumber(state[1])
local updated = tonumber(state[2])

if tokens == nil then
  tokens = capacity
  updated = now
end

-- Refill for the time that has passed, never above capacity.
local elapsed = math.max(0, now - updated) / 1000.0
tokens = math.min(capacity, tokens + elapsed * refill)

local allowed = 0
local retry_after = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  -- How long until enough tokens exist for this request, so the caller can be told rather than
  -- left to poll.
  retry_after = math.ceil(((cost - tokens) / refill) * 1000)
end

redis.call('HSET', bucket, 'tokens', tokens, 'updated', now)
-- Expire an idle bucket after it would have refilled completely; keeping it costs memory and
-- tells us nothing, since a full bucket is indistinguishable from a new one.
redis.call('PEXPIRE', bucket, math.ceil((capacity / refill) * 1000) + 1000)

return { allowed, math.floor(tokens), retry_after }
