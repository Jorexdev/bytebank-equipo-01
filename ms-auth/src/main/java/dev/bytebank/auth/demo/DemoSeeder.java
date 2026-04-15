package dev.bytebank.auth.demo;

import dev.bytebank.auth.user.User;
import dev.bytebank.auth.user.UserRepository;
import dev.bytebank.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usuarios de demostración (perfil demo). Sobre una base de datos vacía quedan con los ids 1, 2 y 3,
 * que son los que usa el seeder de ms-accounts como titulares.
 */
@Component
@Profile("demo")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DemoSeeder(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        users.save(new User("gestor@bytebank.test", passwordEncoder.encode("Gestor1234!"), "Marta Gestora", Role.GESTOR));
        users.save(new User("ana@bytebank.test", passwordEncoder.encode("Cliente1234!"), "Ana Cliente", Role.CLIENTE));
        users.save(new User("luis@bytebank.test", passwordEncoder.encode("Cliente1234!"), "Luis Cliente", Role.CLIENTE));
        log.info("Usuarios de demostración creados");
    }
}
