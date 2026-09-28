-- 上传会话乐观锁列:并发分块 PUT 会同时修改 received_chunks/chunk_hashes/chunk_sizes,
-- 没有版本列时会出现丢失更新(后写覆盖先写),导致 complete 误判缺块(P2 评审 Important#6)。
alter table upload_sessions
    add column if not exists version bigint not null default 0;
