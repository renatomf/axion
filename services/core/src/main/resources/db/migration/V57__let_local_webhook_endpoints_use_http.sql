-- A developer receives webhooks on their own machine at http://localhost
-- on the ports the local profile lists (events and webhooks design,
-- section 7.2). The module accepts such a URL in that profile only; the
-- check still keeps out every other URL that is not https.
ALTER TABLE webhooks.endpoints DROP CONSTRAINT endpoints_url_check;

ALTER TABLE webhooks.endpoints ADD CONSTRAINT endpoints_url_check
    CHECK (char_length(url) <= 2048 AND (url LIKE 'https://%' OR url LIKE 'http://localhost:%'));
