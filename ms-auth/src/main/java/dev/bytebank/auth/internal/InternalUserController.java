package dev.bytebank.auth.internal;

import dev.bytebank.auth.auth.AuthService;
import dev.bytebank.auth.user.UserResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consultas de usuarios para otros servicios (requiere la clave interna). */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    private final AuthService authService;

    public InternalUserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/{id}")
    public UserResponse byId(@PathVariable Long id) {
        return authService.findById(id);
    }

    @GetMapping("/by-email")
    public UserResponse byEmail(@RequestParam String email) {
        return authService.findByEmail(email);
    }
}
