INSERT INTO `sys_event_registry`
(`topic`,`event_code`,`event_name`,`producer`,`consumers`,`ordered`,`msg_type`,`idempotent_hint`) VALUES
('task-topic','TASK_AWARDED','任务中标','award',JSON_ARRAY('order','nonexistent_module'),0,'NORMAL','orderId');