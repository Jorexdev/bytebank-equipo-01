package dev.bytebank.accounts.movement;

import dev.bytebank.accounts.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovementServiceTest {

    @Mock
    MovementRepository movements;

    MovementService service;
    Account account;

    @BeforeEach
    void setUp() {
        service = new MovementService(movements, "Europe/Madrid");
        account = mock(Account.class);
        when(account.getId()).thenReturn(1L);
    }

    @Test
    void returnsTheRequestedPage() {
        List<Movement> firstPage = List.of(movement("Uno"), movement("Dos"), movement("Tres"));
        when(movements.countInRange(anyLong(), any(), any())).thenReturn(3L);
        when(movements.findSlice(anyLong(), any(), any(), anyInt(), anyInt())).thenReturn(firstPage);

        var page = service.statement(account, null, null, 0, 10);

        assertThat(page.content()).hasSize(3);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void filterByDateIncludesLastDay() {
        service.statement(account, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 0, 10);

        // 'to' es inclusivo: el límite superior (exclusivo) es medianoche del día siguiente,
        // en hora de negocio (Madrid, UTC+2 en mayo)
        verify(movements).countInRange(1L,
                Instant.parse("2026-04-30T22:00:00Z"), Instant.parse("2026-05-31T22:00:00Z"));
    }

    @Test
    void rangeIsDelimitedInBusinessTimeZone() {
        service.statement(account, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15), 0, 20);

        // Noviembre en Madrid es UTC+1: 1 nov 00:00 Madrid = 31 oct 23:00Z,
        // 16 nov 00:00 Madrid = 15 nov 23:00Z (así un pago de las 00:30 del 15 sí entra)
        verify(movements).countInRange(1L,
                Instant.parse("2026-10-31T23:00:00Z"), Instant.parse("2026-11-15T23:00:00Z"));
    }

    @Test
    void firstPageStartsAtTheNewestMovement() {
        service.statement(account, null, null, 0, 20);

        // OFFSET en SQL es 0-based: con offset=1 desaparecía el movimiento más reciente
        verify(movements).findSlice(eq(1L), any(), any(), eq(20), eq(0));
    }

    @Test
    void secondPageContinuesWhereTheFirstOneEnds() {
        service.statement(account, null, null, 1, 20);

        verify(movements).findSlice(eq(1L), any(), any(), eq(20), eq(20));
    }

    private Movement movement(String concept) {
        return new Movement(account, MovementType.INGRESO, 10, concept, null);
    }
}
