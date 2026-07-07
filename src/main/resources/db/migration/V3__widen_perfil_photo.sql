-- Widen perfil_photo from 255 to 512 characters. Google CDN profile picture
-- URLs routinely exceed 255 characters (e.g. lh3.googleusercontent.com/...).
-- Uploaded photos use the local /api/v1/user/photo endpoint and do not store
-- a value in this column at all, but existing CDN URLs must fit.
ALTER TABLE users ALTER COLUMN perfil_photo TYPE VARCHAR(512);
