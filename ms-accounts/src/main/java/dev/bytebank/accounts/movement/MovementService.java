package dev.bytebank.accounts.movement;

import dev.bytebank.accounts.account.Account;
import dev.bytebank.common.web.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class MovementService {

    private static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("3000-01-01T00:00:00Z");

    private final MovementRepository movements;
    private final ZoneId zone;

    public MovementService(MovementRepository movements,
                           @Value("${bytebank.zone:Europe/Madrid}") String zone) {
        this.movements = movements;
        this.zone = ZoneId.of(zone);
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementResponse> statement(Account account, LocalDate from, LocalDate to, int page, int size) {
        // Los límites del rango se delimitan en la zona horaria de negocio, no en UTC
        Instant start = from == null ? MIN : from.atStartOfDay(zone).toInstant();
        // 'to' es inclusivo: el límite superior (exclusivo) es el inicio del día siguiente
        Instant end = to == null ? MAX : to.plusDays(1).atStartOfDay(zone).toInstant();

        long total = movements.countInRange(account.getId(), start, end);
        // OFFSET en SQL empieza en 0: la página 0 es la primera
        int offset = page * size;
        List<MovementResponse> content = movements.findSlice(account.getId(), start, end, size, offset)
                .stream().map(m -> MovementResponse.from(m, zone)).toList();
        int totalPages = (int) Math.ceil((double) total / size);
        return new PageResponse<>(content, page, size, total, totalPages);
    }
}
