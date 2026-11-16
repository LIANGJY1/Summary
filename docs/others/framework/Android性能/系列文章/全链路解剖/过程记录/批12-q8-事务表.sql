select t.id, t.ts - 6073694444196 AS rel_ts, t.vsync_id
from __intrinsic_surfaceflinger_transactions t
where t.ts - 6073694444196 >= 6700000000 and t.ts - 6073694444196 <= 6900000000
order by t.ts;
