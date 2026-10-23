-- Accounts, sessions and server-wide settings live in system.db now; this
-- file holds one user's notes. secure_delete zeroes the dropped pages, so
-- password and session hashes and the Resend key don't linger here.
DROP TABLE sessions;
DROP TABLE users;
DROP TABLE settings;
