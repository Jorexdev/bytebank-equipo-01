package dev.bytebank.accounts.movement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MovementRepository extends JpaRepository<Movement, Long> {

    /** Una página del extracto, del más reciente al más antiguo. */
    @Query(value = """
            select * from movements
            where account_id = :accountId and created_at >= :start and created_at < :end
            order by created_at desc, id desc
            limit :limit offset :offset
            """, nativeQuery = true)
    List<Movement> findSlice(@Param("accountId") Long accountId, @Param("start") Instant start,
                             @Param("end") Instant end, @Param("limit") int limit, @Param("offset") int offset);

    @Query("""
            select count(m) from Movement m
            where m.account.id = :accountId and m.createdAt >= :start and m.createdAt < :end
            """)
    long countInRange(@Param("accountId") Long accountId, @Param("start") Instant start, @Param("end") Instant end);
}
