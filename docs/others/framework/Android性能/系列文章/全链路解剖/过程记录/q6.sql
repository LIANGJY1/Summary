-- onVsync 总数、input/animation/commit 模糊匹配
SELECT
 (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread th USING(utid) WHERE th.tid=1909 AND s.name LIKE '%onVsync%') AS onvsync_cnt,
 (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread th USING(utid) WHERE th.tid=1909 AND s.name LIKE 'input%') AS input_cnt,
 (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread th USING(utid) WHERE th.tid=1909 AND s.name LIKE 'animation%') AS anim_cnt,
 (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread th USING(utid) WHERE th.tid=1909 AND s.name LIKE 'commit%') AS commit_cnt,
 (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread th USING(utid) WHERE th.tid=1909 AND s.name LIKE 'Choreographer#doFrame%') AS doframe_cnt;
