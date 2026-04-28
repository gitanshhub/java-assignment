INSERT INTO users (id, username, is_premium)
VALUES (1, 'alice', true),
       (2, 'bob', false)
ON CONFLICT (id) DO NOTHING;

INSERT INTO bots (id, name, persona_description)
VALUES (1, 'Nova', 'Helpful assistant bot'),
       (2, 'Atlas', 'Analytical assistant bot')
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST((SELECT COALESCE(MAX(id), 1) FROM users), 1), true);
SELECT setval('bots_id_seq', GREATEST((SELECT COALESCE(MAX(id), 1) FROM bots), 1), true);
