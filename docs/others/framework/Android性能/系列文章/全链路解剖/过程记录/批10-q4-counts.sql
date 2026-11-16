-- 批10 q4：tid2079 全 trace DrawFrames 计数与 tid1909 doFrame 计数对照
SELECT (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid)
        WHERE t.tid=2079 AND s.name LIKE 'DrawFrames %') AS drawframes_count,
       (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid)
        WHERE t.tid=1909 AND s.name LIKE 'Choreographer#doFrame %') AS doframe_count,
       (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid)
        WHERE t.tid=2079 AND s.name='syncFrameState') AS syncframestate_count,
       (SELECT COUNT(*) FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid)
        WHERE t.tid=2079 AND s.name='flush commands') AS flush_count;
