ALTER TABLE notifications
    ADD CONSTRAINT uq_notifications_user_movement UNIQUE (user_id, movement_id);