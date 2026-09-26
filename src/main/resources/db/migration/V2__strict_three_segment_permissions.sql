-- Replace the legacy one-segment super permission with the strict three-segment form.
UPDATE sys_permission
   SET perm = '*:*:*',
       name = '超级权限',
       remark = '三段式 glob 通配全部权限，仅授予站长'
 WHERE perm = '*';
