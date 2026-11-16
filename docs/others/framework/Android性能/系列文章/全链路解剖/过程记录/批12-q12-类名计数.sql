SELECT CASE
  WHEN s.name LIKE 'commit %' THEN 'commit*'
  WHEN s.name LIKE 'composite %' THEN 'composite*'
  ELSE s.name END AS kind, COUNT(*) AS n
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.upid = 50
  AND s.ts - 6073694444196 >= 6700000000 AND s.ts - 6073694444196 <= 6900000000
  AND (s.name LIKE 'commit %' OR s.name LIKE 'composite %' OR s.name = 'latchBuffers'
       OR s.name = 'present' OR s.name = 'presentAndGetReleaseFences' OR s.name = 'HwcPresentDisplay'
       OR s.name = 'composeSurfaces' OR s.name = 'prepareFrameAsync' OR s.name = 'prepareFrame')
GROUP BY kind ORDER BY n DESC;
