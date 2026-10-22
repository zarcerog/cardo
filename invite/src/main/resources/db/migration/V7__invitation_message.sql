-- Optional product-supplied invitation email. Both columns are null for
-- invitations that use Invite's default email. When present, message_body
-- contains the literal {{acceptUrl}} placeholder exactly once; Invite
-- substitutes the accept URL at send time so the secret token is never stored
-- in the message. Stored for retried deliveries and never returned by a read.
ALTER TABLE invitations
  ADD COLUMN message_subject text,
  ADD COLUMN message_body text;
