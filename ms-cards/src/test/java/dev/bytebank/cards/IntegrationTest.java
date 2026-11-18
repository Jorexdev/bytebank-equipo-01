package dev.bytebank.cards;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Infraestructura compartida por los tests de integración.
 * El contenedor es un singleton: se arranca una vez por JVM y no se para entre clases de test.
 * Spring cachea los contextos por configuración, así que si el contenedor cambiara de puerto
 * entre clases el contexto cacheado seguiría apuntando al puerto anterior.
 */
@SpringBootTest(properties = "bytebank.internal-key=clave-interna-de-test")
@ActiveProfiles("test")
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
