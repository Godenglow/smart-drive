package com.smartdrive.order.mapper;

import com.smartdrive.order.entity.OutboxEvent;
import com.smartdrive.order.entity.OutboxBacklog;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxMapper {
    @Select("""
            SELECT COUNT(*) AS pending,
                COALESCE(GREATEST(0, TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(3)) DIV 1000), 0) AS oldest_age_ms
            FROM outbox_event WHERE status = 'NEW'
            """)
    OutboxBacklog backlog();

    @Insert("""
            INSERT INTO outbox_event(event_id, aggregate_id, event_type, payload)
            VALUES(#{eventId}, #{aggregateId}, #{eventType}, #{payload})
            """)
    int insert(OutboxEvent event);

    @Select("SELECT id FROM outbox_event WHERE status = 'NEW' ORDER BY created_at, id LIMIT #{limit}")
    List<Long> pendingIds(int limit);

    // A short transaction owns the row until the bounded broker confirm wait finishes.
    // Other publishers skip it; a crash releases the lock and leaves the event retryable.
    @Select("SELECT * FROM outbox_event WHERE id = #{id} AND status = 'NEW' FOR UPDATE SKIP LOCKED")
    OutboxEvent lockPending(long id);

    @Update("""
            UPDATE outbox_event SET attempts = attempts + 1,
                status = CASE WHEN #{sent} THEN 'SENT' ELSE 'NEW' END,
                sent_at = CASE WHEN #{sent} THEN UTC_TIMESTAMP(3) ELSE NULL END
            WHERE id = #{id} AND status = 'NEW'
            """)
    int recordAttempt(@Param("id") long id, @Param("sent") boolean sent);
}
