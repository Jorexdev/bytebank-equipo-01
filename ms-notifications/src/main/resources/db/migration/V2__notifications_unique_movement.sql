-- Antes de KAF-03 podía haber avisos repetidos del mismo movimiento: se conserva el más antiguo.
DELETE FROM notifications n
    USING notifications d
WHERE n.user_id = d.user_id AND n.movement_id = d.movement_id AND n.id > d.id;

ALTER TABLE notifications
    ADD CONSTRAINT uq_notifications_user_movement UNIQUE (user_id, movement_id);