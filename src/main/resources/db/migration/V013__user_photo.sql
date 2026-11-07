-- A profile photo for every user account (shown next to the name in the top bar and in the user list).
ALTER TABLE app_user ADD COLUMN photo_file_id BIGINT;
