package dev.bytebank.common.security;

/** Usuario autenticado, sacado del JWT. */
public record AuthUser(Long id, String email, Role role) {

    public boolean isGestor() {
        return role == Role.GESTOR;
    }
}
